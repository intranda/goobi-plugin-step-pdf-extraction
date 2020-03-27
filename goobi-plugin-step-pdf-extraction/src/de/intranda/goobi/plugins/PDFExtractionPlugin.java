package de.intranda.goobi.plugins;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.log4j.Logger;
import org.goobi.beans.Process;
import org.goobi.beans.Processproperty;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IPlugin;
import org.goobi.production.plugin.interfaces.IStepPlugin;
import org.jdom2.JDOMException;

import de.intranda.digiverso.errorhandling.FilesReverter;
import de.intranda.digiverso.errorhandling.ReversionException;
import de.intranda.digiverso.pdf.PDFConverter;
import de.intranda.digiverso.pdf.exception.PDFReadException;
import de.intranda.digiverso.pdf.exception.PDFWriteException;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.PropertyManager;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import spark.utils.StringUtils;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.Fileformat;
import ugh.dl.Metadata;
import ugh.dl.MetadataType;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;

@PluginImplementation
public class PDFExtractionPlugin implements IPlugin, IStepPlugin {

    private static final String TITLE = "intranda_step_pdf-extraction";

    private static final Logger logger = Logger.getLogger(PDFExtractionPlugin.class);
    private static final String DEFAULT_ENCODING = "utf-8";

    private File tifFolder = null;
    private File importFolder = null;
    private File pdfFolder = null;
    private File textFolder = null;
    private File altoFolder = null;

    private Configuration config;
    private FilesReverter reverter = new FilesReverter();

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

    /**
     * Execute the plugin. This method is the entrypoint called by Goobi.
     */
    @Override
    public boolean execute() {

        Process process = step.getProzess();
        this.config = getConfig();
        try {
            try {
                Path importFolder = Paths.get(process.getImagesOrigDirectory(true));
                List<File> pdfFiles = StorageProvider.getInstance()
                        .listFiles(importFolder.toString(), (path) -> path.toString().matches(".*.(pdf|PDF)"))
                        .stream()
                        .map(Path::toFile)
                        .collect(Collectors.toList());

                if (pdfFiles.size() > 0) {
                    Fileformat ff = convertData(pdfFiles, process);
                    if (ff != null) {
                        try {
                            backupMetadata(process);
                            ff.write(process.getMetadataFilePath());
                            createProcessProperties();
                            Helper.addMessageToProcessLog(process.getId(), LogType.INFO, "Added " + pdfFiles.size() + " pdf files to process");
                            return true;
                        } catch (IOException | InterruptedException | SwapException | DAOException | WriteException | PreferencesException e) {
                            logger.error("Error writing new metadata file");
                        }
                    } else {
                        throw new IOException("Failed to extract pdf files");
                    }
                } else {
                    logger.error("No PDF files found in " + importFolder);
                    Helper.addMessageToProcessLog(process.getId(), LogType.ERROR,
                            "Failed to perform PDF-extraction: No pdf files found in " + importFolder);
                }
            } catch (IllegalArgumentException e) {
                logger.error("Illegal image format for image creation");
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "Illegal image format for image creation");
                reverter.revert(true);
            } catch (UGHException e) {
                logger.error("Error creating metadata", e);
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "Error adding pdf to process:\n" + e.toString());
                reverter.revert(true);
            } catch (PDFWriteException e) {
                logger.error("Error creating single page pdf files", e);
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "Error creating single page pdf files:\n" + e.toString());
                reverter.revert(true);
            } catch (DAOException | IOException | InterruptedException | SwapException e) {
                logger.error("Error getting process directory paths", e);
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "Error adding pdf to process:\n" + e.toString());
                reverter.revert(true);
            } catch (Throwable e) {
                logger.error("Unexpected error", e);
                Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "Error adding pdf to process:\n" + e.toString());
                reverter.revert(true);
            }
        } catch (ReversionException e) {
            logger.error("Error reverting process after exception", e);
            Helper.addMessageToProcessLog(process.getId(), LogType.ERROR, "Error reverting process after exception:\n" + e.toString());

        }
        return false;
    }

    /**
     * Write configured process properties
     * 
     * @throws PDFReadException
     */
    private void createProcessProperties() throws PDFReadException {
        String propertyName = this.config.getString("properties.fulltext.name", "");
        if (StringUtils.isNotBlank(propertyName)) {
            String trueValue = this.config.getString("properties.fulltext.value[@exists='true']", "TRUE");
            String falseValue = this.config.getString("properties.fulltext.value[@exists='false']", "FALSE");

            List<File> pdfFiles = StorageProvider.getInstance()
                    .listFiles(this.importFolder.toString(), (path) -> path.toString().matches(".*.(pdf|PDF)"))
                    .stream()
                    .map(Path::toFile)
                    .collect(Collectors.toList());
            boolean hasFulltext = getFulltext(pdfFiles);
            setProperty(propertyName, hasFulltext ? trueValue : falseValue);
        }

    }

    private void setProperty(String propertyName, String value) {
        List<Processproperty> properties = this.step.getProzess().getEigenschaftenList();
        Processproperty property = properties.stream().filter(p -> p.getTitel().equalsIgnoreCase(propertyName)).findFirst().orElse(null);
        if (property == null) {
            property = new Processproperty();
            property.setTitel(propertyName);
            property.setContainer(0);
            property.setCreationDate(Date.from(Instant.now()));
            property.setProzess(this.step.getProzess());
            properties.add(property);
        }
        property.setWert(value);
        PropertyManager.saveProcessProperty(property);
    }

    private boolean getFulltext(List<File> pdfFiles) throws PDFReadException {
        for (File file : pdfFiles) {
            if (StringUtils.isNotBlank(PDFConverter.getFulltext(file))) {
                return true;
            }
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
        if (Files.isRegularFile(oldMetsFile)) {
            Path backupFile = Paths.get(oldMetsFile.toString() + ".BACKUP");
            Files.move(oldMetsFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            reverter.addCreatedPath(oldMetsFile.toFile());
            reverter.addMovedPath(oldMetsFile.toFile(), backupFile.toFile());
            Path oldAnchorFile = Paths.get(oldMetsFile.toString().replace(".xml", "_anchor.xml"));
            if (Files.isRegularFile(oldAnchorFile)) {
                Path backupAnchorFile = Paths.get(oldAnchorFile.toString() + ".BACKUP");
                Files.move(oldAnchorFile, backupAnchorFile, StandardCopyOption.REPLACE_EXISTING);
                reverter.addCreatedPath(oldAnchorFile.toFile());
                reverter.addMovedPath(oldAnchorFile.toFile(), backupAnchorFile.toFile());
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

    /**
     * Converts the PDF files in importFiles, writing Metadata and TOC information to process.
     * 
     * @param importFiles
     * @param process
     * @return
     * @throws IOException
     * @throws InterruptedException
     * @throws SwapException
     * @throws DAOException
     * @throws PDFReadException
     * @throws PDFWriteException
     * @throws UGHException
     */
    public Fileformat convertData(List<File> importFiles, Process process)
            throws IOException, InterruptedException, SwapException, DAOException, PDFReadException, PDFWriteException, UGHException {

        Fileformat origFileformat = process.readMetadataFile();
        Prefs prefs = process.getRegelsatz().getPreferences();

        tifFolder = new File(process.getImagesOrigDirectory(false));
        importFolder = new File(process.getImportDirectory());

        pdfFolder = new File(process.getOcrPdfDirectory());
        textFolder = new File(process.getOcrTxtDirectory());
        altoFolder = new File(process.getOcrAltoDirectory());

        tifFolder.mkdirs();
        importFolder.mkdirs();
        pdfFolder.mkdirs();
        textFolder.mkdirs();
        altoFolder.mkdirs();

        Fileformat ff = origFileformat;
        DocStruct topStruct = getTopStruct(ff);
        DocStruct boundBook = ff.getDigitalDocument().getPhysicalDocStruct();
        int numExistingPages = boundBook.getAllChildren() == null ? 0 : boundBook.getAllChildren().size();

        MutableInt counter = new MutableInt(numExistingPages + 1);
        String pdfDocType = config.getString("docType.parent", "");
        String childDocType = config.getString("docType.children", "");
        for (File file : importFiles) {
            if (StringUtils.isNotBlank(pdfDocType)) {
                DocStruct ds = addDocStruct(topStruct, ff, prefs, pdfDocType, file);
                ff = convertPdf(file, ff, prefs, ds, childDocType, counter);
            } else {
                ff = convertPdf(file, ff, prefs, null, childDocType, counter);
            }
        }
        logger.debug("A total of " + (counter.intValue() - 1) + " pages have so far been converted");
        return ff;

    }

    private DocStruct getTopStruct(Fileformat ff) throws PreferencesException {
        DocStruct top = ff.getDigitalDocument().getLogicalDocStruct();
        if (top.getType().isAnchor() && !top.getAllChildren().isEmpty()) {
            top = top.getAllChildren().get(0);
        }
        return top;
    }

    @SuppressWarnings("unchecked")
    private DocStruct addDocStruct(DocStruct parent, Fileformat ff, Prefs prefs, String docTypeName, File file) throws UGHException {

        DocStructType dsType = prefs.getDocStrctTypeByName(docTypeName);
        DocStruct ds = ff.getDigitalDocument().createDocStruct(dsType);
        parent.addChild(ds);
        return ds;
    }

    private Optional<Metadata> addMetadataIfAllowed(String typeName, DocStruct ds, Prefs prefs) {
        if (StringUtils.isNotBlank(typeName)) {
            try {
                MetadataType type = prefs.getMetadataTypeByName(typeName);
                if (type != null) {
                    Metadata md = new Metadata(type);
                    ds.addMetadata(md);
                    return Optional.of(md);
                } else {
                    throw new UGHException("MetadataType " + typeName + " not found in prefs");
                }
            } catch (UGHException e) {
                logger.warn("Unable to addMetadata " + typeName + " to " + ds.getType().getName() + ". Reason: " + e.toString());
            }
        }
        return Optional.empty();
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
    private Fileformat convertPdf(File importFile, Fileformat origFileformat, Prefs prefs, DocStruct parent, String childDocType, MutableInt counter)
            throws PDFReadException, PDFWriteException, PreferencesException, IOException {
        File importPdfFile = PDFConverter.decryptPdf(importFile, importFolder);
        if (importPdfFile == null || !importPdfFile.exists()) {
            importPdfFile = new File(importFolder, importFile.getName());
            FileUtils.moveFile(importFile, importPdfFile);
            logger.debug("Copied original PDF file to " + importPdfFile);
        } else {
            logger.debug("Created decrypted PDF file at " + importPdfFile);
        }
        reverter.addMovedPath(importFile, importPdfFile);

        int imageResolution = config.getInt("images.resolution", 300);
        String imageFormat = config.getString("images.format", "tif");

        List<File> textFiles = PDFConverter.writeFullText(importPdfFile, textFolder, DEFAULT_ENCODING, counter.toInteger());
        reverter.addCreatedPaths(textFiles);
        logger.debug("Created " + textFiles.size() + " text files in " + textFolder);
        List<File> pdfFiles = PDFConverter.writeSinglePagePdfs(importPdfFile, pdfFolder, counter.toInteger());
        reverter.addCreatedPaths(pdfFiles);
        logger.debug("Created " + pdfFiles.size() + " PDF files in " + pdfFolder);
        List<File> imageFiles = PDFConverter.writeImages(importPdfFile, tifFolder, counter.toInteger(), imageResolution, imageFormat);
        reverter.addCreatedPaths(imageFiles);
        logger.debug("Created " + imageFiles.size() + " TIFF files in " + tifFolder);
        List<File> altoFiles = writeAltoFiles(altoFolder, pdfFiles, imageFiles);
        reverter.addCreatedPaths(altoFiles);
        logger.debug("Created " + altoFiles.size() + " ALTO files in " + altoFolder);
        Fileformat ff = PDFConverter.writeFileformat(importPdfFile, imageFiles, origFileformat, prefs, counter.toInteger(), parent, childDocType);
        logger.debug("Created Mets/Mods fileformat from PDF");

        counter.add(Math.max(pdfFiles.size(), imageFiles.size()));

        return ff;
    }

    /**
     * @param altoFolder
     * @param pdfFiles
     * @param imageFiles
     * @return
     * @throws JDOMException
     * @throws IOException
     */
    private List<File> writeAltoFiles(File altoFolder, List<File> pdfFiles, List<File> imageFiles) throws PDFReadException, PDFWriteException {
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

    protected Configuration getConfig() {
        return ConfigPlugins.getPluginConfig(this.getTitle());
    }
}
