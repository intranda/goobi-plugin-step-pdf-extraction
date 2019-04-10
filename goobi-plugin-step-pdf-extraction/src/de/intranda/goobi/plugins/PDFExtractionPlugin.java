package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.Step;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;
import org.jdom2.JDOMException;

import de.intranda.digiverso.pdf.PDFConverter;
import de.intranda.digiverso.pdf.exception.PDFWriteException;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;

@PluginImplementation
public class PDFExtractionPlugin implements IPlugin, IStepPlugin {

	private static final String TITLE = "intranda_step_extract_pdf";

	private static final Logger logger = Logger.getLogger(PDFExtractionPlugin.class);
	private static final String DEFAULT_ENCODING = "utf-8";

	private File tifFolder = null;
	private File sourceFolder = null;
	private File pdfFolder = null;
	private File textFolder = null;
	private File altoFolder = null;
	
	private Step step;
	private String returnPath;

	@Override
	public PluginType getType() {
		return PluginType.Step;
	}

	@Override
	public String getTitle() {
		return TITLE;
	}

	@Override
	public void initialize(Step step, String returnPath) {
		this.step = step;
		this.returnPath = returnPath;
	}

	@Override
	public boolean execute() {
		
		Process process = step.getProzess();
		try {
		Path importFolder = Paths.get(process.getImagesOrigDirectory(true));
		List<File> pdfFiles = StorageProvider.getInstance().listFiles(importFolder.toString(), (path) -> path.toString().matches(".*.(pdf|PDF)"))
		        .stream().map(Path::toFile).collect(Collectors.toList());
		
		if(pdfFiles.size() > 0) {
			Fileformat ff = convertData(pdfFiles, process);
			if(ff != null) {
				try {
					backupMetadata(process);
					ff.write(process.getMetadataFilePath());
					for (File file : pdfFiles) {
                        file.delete();
                    }
					return true;
				} catch (IOException | InterruptedException | SwapException | DAOException | WriteException | PreferencesException e) {
					logger.error("Error writing new metadata file");
				}
			} else {
				logger.error("Failed to extract pdf files ");
			}
		} else {
			logger.error("No PDF files found in " + importFolder);
		}
		} catch(DAOException | IOException | InterruptedException | SwapException e) {
			logger.error("Error getting process directory paths", e);
		}
		return false;
	}

	/**
	 * @param process
	 * @throws IOException
	 * @throws InterruptedException
	 * @throws SwapException
	 * @throws DAOException
	 */
	private void backupMetadata(Process process) throws IOException, InterruptedException, SwapException, DAOException {
		Path oldMetsFile = Paths.get(process.getMetadataFilePath());
		if(Files.isRegularFile(oldMetsFile)) {
			Path backupFile = Paths.get(oldMetsFile.toString() + ".BACKUP");
			Files.move(oldMetsFile, backupFile);
			Path oldAnchorFile = Paths.get(oldMetsFile.toString().replace(".xml", "_anchor.xml"));
			if(Files.isRegularFile(oldAnchorFile)) {
				Path backupAnchorFile = Paths.get(oldAnchorFile.toString() + ".BACKUP");
				Files.move(oldAnchorFile, backupAnchorFile);
			}
		}
	}

	@Override
	public String cancel() {
		return returnPath;
	}

	@Override
	public String finish() {
		return returnPath;
	}

	@Override
	public HashMap<String, StepReturnValue> validate() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Step getStep() {
		return this.step;
	}

	@Override
	public PluginGuiType getPluginGuiType() {
		return PluginGuiType.NONE;
	}

	@Override
	public String getPagePath() {
		return null;
	}

	public Fileformat convertData(List<File> importFiles, Process process) {



		try {
			Fileformat origFileformat = process.readMetadataFile();
			Prefs prefs = process.getRegelsatz().getPreferences();

			tifFolder = new File(process.getImagesOrigDirectory(false));
			sourceFolder = new File(process.getSourceDirectory());

			pdfFolder = new File(process.getOcrPdfDirectory());
			textFolder = new File(process.getOcrTxtDirectory());
			altoFolder = new File(process.getOcrAltoDirectory());

			tifFolder.mkdirs();
			sourceFolder.mkdirs();
			pdfFolder.mkdirs();
			textFolder.mkdirs();
			altoFolder.mkdirs();

			Fileformat ff = origFileformat;
			MutableInt counter = new MutableInt(1);
			for (File file : importFiles) {
			    ff = convertPdf(file, ff, prefs, counter);
            }
			logger.debug("A total of " + (counter.intValue()-1) + " pages have been converted");
			return ff;
		
		} catch (IOException | PDFWriteException | JDOMException | PreferencesException | SwapException | DAOException
				| InterruptedException | ReadException | WriteException e) {
			logger.error("Error converting pdf file to goobi files ", e);
			revert(importFiles, tifFolder, sourceFolder, pdfFolder, pdfFolder, textFolder, altoFolder);
			logger.debug("Reverted all folders");
			return null;
		}

	}

    /**
     * @param sourceFolder
     * @param tifFolder
     * @param pdfFolder
     * @param textFolder
     * @param altoFolder
     * @param origFileformat
     * @param prefs
     * @return
     * @throws IOException
     * @throws PDFWriteException
     * @throws JDOMException
     * @throws PreferencesException
     */
    private Fileformat convertPdf(File importFile, Fileformat origFileformat, Prefs prefs, MutableInt counter) throws IOException, PDFWriteException, JDOMException, PreferencesException {
        File importPdfFile = PDFConverter.decryptPdf(importFile, sourceFolder);
        if (importPdfFile == null || !importPdfFile.exists()) {
        	importPdfFile = new File(sourceFolder, importFile.getName());
        	FileUtils.copyFile(importFile, importPdfFile);
        	logger.debug("Copied original PDF file to " + importPdfFile);
        } else {
        	logger.debug("Created decrypted PDF file at " + importPdfFile);
        }

        List<File> textFiles = PDFConverter.writeFullText(importPdfFile, textFolder, DEFAULT_ENCODING, counter.toInteger());
        logger.debug("Created " + textFiles.size() + " text files in " + textFolder);
        List<File> pdfFiles = PDFConverter.writeSinglePagePdfs(importPdfFile, pdfFolder, counter.toInteger());
        logger.debug("Created " + pdfFiles.size() + " PDF files in " + pdfFolder);
        List<File> imageFiles = PDFConverter.writeImages(importPdfFile, tifFolder, counter.toInteger());
        logger.debug("Created " + imageFiles.size() + " TIFF files in " + tifFolder);
        List<File> altoFiles = writeAltoFiles(altoFolder, pdfFiles, imageFiles);
        logger.debug("Created " + altoFiles.size() + " ALTO files in " + altoFolder);
        Fileformat ff = PDFConverter.writeFileformat(importPdfFile, imageFiles, origFileformat, prefs, counter.toInteger());
        logger.debug("Created Mets/Mods fileformat from PDF");
        
        counter.add(Math.max(pdfFiles.size(), imageFiles.size()));
        
        return ff;
    }

	private void revert(List<File> importFiles, File... foldersToDelete) {
		
	    Map<File, File> tempFiles = new HashMap<>();
	    
		
		try {
		    for (File importFile : importFiles) {
		        File tempFile = File.createTempFile(importFile.getName(), ".pdf");
		        FileUtils.moveFile(importFile, tempFile);
		        tempFiles.put(tempFile, importFile.getParentFile());
                
            }
			
			for (File folder : foldersToDelete) {
				FileUtils.deleteDirectory(folder);
			}
			
			for (File tempFile : tempFiles.keySet()) {
                File targetFile = tempFiles.get(tempFile);
			    if(!targetFile.getParentFile().isDirectory()) {
			        targetFile.getParentFile().mkdirs();
			    }
			    FileUtils.moveFile(tempFile, targetFile);
            }
			
			
		} catch (IOException e) {
			logger.error("Failed to revert files", e);
			return;
		}
		
	}

	/**
	 * @param altoFolder
	 * @param pdfFiles
	 * @param imageFiles
	 * @return
	 * @throws JDOMException
	 * @throws IOException
	 */
	private List<File> writeAltoFiles(File altoFolder, List<File> pdfFiles, List<File> imageFiles)
			throws JDOMException, IOException {
		List<File> altoFiles = new ArrayList<>();
		for (int i = 0; i < pdfFiles.size(); i++) {
			File pdfFile = pdfFiles.get(i);
			File imageFile = null;
			if (i < imageFiles.size()) {
				imageFile = imageFiles.get(i);
			}
			File altoFile = PDFConverter.writeAltoFile(pdfFile, altoFolder, imageFile, false);
			altoFiles.add(altoFile);
		}
		return altoFiles;
	}

}
