package de.intranda.goobi.plugins;

/**
 * This file is part of a plugin for the Goobi Application - a Workflow tool for the support of mass digitization.
 * 
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 * 
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 * 
 * Linking this library statically or dynamically with other modules is making a combined work based on this library. Thus, the terms and conditions
 * of the GNU General Public License cover the whole combination. As a special exception, the copyright holders of this library give you permission to
 * link this library with independent modules to produce an executable, regardless of the license terms of these independent modules, and to copy and
 * distribute the resulting executable under terms of your choice, provided that you also meet, for each linked independent module, the terms and
 * conditions of the license of that module. An independent module is a module which is not derived from or based on this library. If you modify this
 * library, you may extend this exception to your version of the library, but you are not obliged to do so. If you do not wish to do so, delete this
 * exception statement from your version.
 */
import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Date;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.apache.commons.configuration.XMLConfiguration;
import org.apache.commons.configuration.tree.ExpressionEngine;
import org.apache.commons.configuration.tree.xpath.XPathExpressionEngine;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.Logger;
import org.goobi.beans.GoobiProperty;
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
import de.intranda.digiverso.files.naming.NumberFormatNamer;
import de.intranda.digiverso.files.naming.PdfFilenameNamer;
import de.intranda.digiverso.pdf.PDFConverter;
import de.intranda.digiverso.pdf.exception.PDFReadException;
import de.intranda.digiverso.pdf.exception.PDFWriteException;
import de.intranda.goobi.exceptions.PluginConfigurationException;
import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.config.ConfigurationHelper;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.NIOFileUtils;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.VariableReplacer;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import de.sub.goobi.persistence.managers.PropertyManager;
import net.xeoh.plugins.base.annotations.PluginImplementation;
import ugh.dl.DocStruct;
import ugh.dl.DocStructType;
import ugh.dl.FileSet;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.UGHException;
import ugh.exceptions.WriteException;

@PluginImplementation
public class PDFExtractionPlugin implements IPlugin, IStepPlugin {

    private static final String TITLE = "intranda_step_pdf-extraction";

    private static final Logger logger = Logger.getLogger(PDFExtractionPlugin.class);
    private static final String DEFAULT_ENCODING = "utf-8";

    private Path tifFolder = null;
    private Path importFolder = null;
    private Path pdfFolder = null;
    private Path textFolder = null;
    private Path altoFolder = null;

    private Configuration config;
    private FilesReverter reverter = new FilesReverter();

    private Step step;
    private String returnPath;

    private boolean useS3 = false;

    private Path tempFolder = null;

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
        try {
            this.config = getConfig(process.getProjekt().getTitel(), step.getTitel());
            try {
                Fileformat origFileformat = process.readMetadataFile();
                Prefs prefs = process.getRegelsatz().getPreferences();
                VariableReplacer vr = new VariableReplacer(origFileformat.getDigitalDocument(), prefs, process, step);
                Path sourceFolder = Path.of(getConfigValue("sourceFolder", "{origpath}", vr));
                List<File> pdfFiles = StorageProvider.getInstance()
                        .listFiles(sourceFolder.toString(), (path) -> path.toString().matches(".*.(pdf|PDF)"))
                        .stream()
                        .map(Path::toFile)
                        .collect(Collectors.toList());

                if (pdfFiles.size() > 0) {
                    if (ConfigurationHelper.getInstance().useS3()) {
                        useS3 = true;
                        // create temp folder
                        tempFolder = Paths.get(ConfigurationHelper.getInstance().getTemporaryFolder(), "" + process.getId());
                        if (!Files.exists(tempFolder)) {
                            Files.createDirectories(tempFolder);
                        }
                        // download files
                        StorageProvider.getInstance().downloadDirectory(sourceFolder, tempFolder);
                        // set temp folder as import folder
                        pdfFiles = StorageProvider.getInstance()
                                .listFiles(tempFolder.toString(), (path) -> path.toString().matches(".*\\.(pdf|PDF)"))
                                .stream()
                                .map(Path::toFile)
                                .collect(Collectors.toList());
                    }
                    Fileformat ff = convertData(pdfFiles, origFileformat, prefs, vr, config.getBoolean("overwriteExistingData", true));
                    if (ff != null) {
                        try {
                            if (shouldWriteMetsFile()) {
                                backupMetadata(process);
                                ff.write(process.getMetadataFilePath());
                            }
                            createProcessProperties();
                            Helper.addMessageToProcessJournal(process.getId(), LogType.INFO, "Added " + pdfFiles.size() + " pdf files to process");

                            if (useS3) {
                                // upload files, cleanup temp folder
                                StorageProvider.getInstance()
                                        .uploadDirectory(tifFolder, Path.of(getConfigValue("images.destination", "{origpath}", vr)));
                                StorageProvider.getInstance()
                                        .uploadDirectory(this.importFolder, Path.of(getConfigValue("targetFolder", "{importpath}", vr)));
                                StorageProvider.getInstance()
                                        .uploadDirectory(pdfFolder,
                                                Path.of(getConfigValue("pagePdfs.destination", "{processpath}/ocr/{processtitle}_pdf", vr)));
                                StorageProvider.getInstance()
                                        .uploadDirectory(textFolder,
                                                Path.of(getConfigValue("plaintext.destination", "{processpath}/ocr/{processtitle}_txt", vr)));
                                StorageProvider.getInstance()
                                        .uploadDirectory(altoFolder,
                                                Path.of(getConfigValue("alto.destination", "{processpath}/ocr/{processtitle}_alto", vr)));
                                StorageProvider.getInstance().deleteDir(tempFolder);
                                // remove original pdf files
                                pdfFiles = StorageProvider.getInstance()
                                        .listFiles(sourceFolder.toString(), (path) -> path.toString().matches(".*.(pdf|PDF)"))
                                        .stream()
                                        .map(Path::toFile)
                                        .collect(Collectors.toList());
                                for (File pdf : pdfFiles) {
                                    StorageProvider.getInstance().deleteFile(pdf.toPath());
                                }
                            }
                            return true;
                        } catch (IOException | InterruptedException | SwapException | DAOException | WriteException | PreferencesException e) {
                            logger.error("Error writing new metadata file: " + e.toString());
                        }
                    } else {
                        throw new IOException("Failed to extract pdf files");
                    }
                } else if (config.getBoolean("validation.failOnMissingPDF", true)) {
                    logger.error("No PDF files found in " + sourceFolder);
                    Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR,
                            "Failed to perform PDF-extraction: No pdf files found in " + sourceFolder);

                } else {
                    logger.debug("No PDF files found in " + sourceFolder);
                    Helper.addMessageToProcessJournal(process.getId(), LogType.DEBUG,
                            "No PDF files found in " + sourceFolder + ". Continue workflow without PDF conversion");
                    return true;
                }
            } catch (IllegalArgumentException e) {
                logger.error("Illegal image format for image creation");
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "Illegal image format for image creation");
                reverter.revert(true);
            } catch (UGHException e) {
                logger.error("Error creating metadata", e);
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "Error adding pdf to process:\n" + e.toString());
                reverter.revert(true);
            } catch (PDFWriteException e) {
                logger.error("Error creating single page pdf files", e);
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "Error creating single page pdf files:\n" + e.toString());
                reverter.revert(true);
            } catch (PDFReadException e) {
                logger.error("Error creating files", e);
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "Error creating files:\n" + e.toString());
                reverter.revert(true);
            } catch (DAOException | IOException | InterruptedException | SwapException e) {
                logger.error("Error getting process directory paths", e);
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "Error adding pdf to process:\n" + e.toString());
                reverter.revert(true);
            } catch (Throwable e) {
                logger.error("Unexpected error", e);
                Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "Error adding pdf to process:\n" + e.toString());
                reverter.revert(true);
            }
        } catch (ReversionException e) {
            logger.error("Error reverting process after exception", e);
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, "Error reverting process after exception:\n" + e.toString());
        } catch (PluginConfigurationException e) {
            logger.error(e.getMessage(), e);
            Helper.addMessageToProcessJournal(process.getId(), LogType.ERROR, e.toString());
        }
        return false;
    }

    private String getConfigValue(String key, String defaultValue, VariableReplacer vr) {
        String configuredValue = this.config.getString(key, defaultValue);
        if (vr != null) {
            return vr.replace(configuredValue);
        } else {
            return configuredValue;
        }
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

            boolean hasFulltext = false;
            if (this.altoFolder != null && Files.exists(this.altoFolder) && StorageProvider.getInstance().getNumberOfFiles(altoFolder) > 0) {
                hasFulltext = true;
            } else if (this.textFolder != null && Files.exists(this.textFolder) && StorageProvider.getInstance().getNumberOfFiles(textFolder) > 0) {
                hasFulltext = true;
            }
            setProperty(propertyName, hasFulltext ? trueValue : falseValue);
        }
    }

    private void setProperty(String propertyName, String value) {
        List<GoobiProperty> properties = this.step.getProzess().getEigenschaftenList();
        GoobiProperty property = properties.stream().filter(p -> p.getTitel().equalsIgnoreCase(propertyName)).findFirst().orElse(null);
        if (property == null) {
            property = new Processproperty();
            property.setTitel(propertyName);
            property.setContainer("0");
            property.setCreationDate(Date.from(Instant.now()));
            property.setOwner(this.step.getProzess());
            properties.add(property);
        }
        property.setWert(value);
        PropertyManager.saveProperty(property);
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
    public Fileformat convertData(List<File> importFiles, Fileformat origFileformat, Prefs prefs, VariableReplacer vr, boolean overwriteOldData)
            throws IOException, InterruptedException, SwapException, DAOException, PDFReadException, PDFWriteException, UGHException {

        preparePDFConverter();

        tifFolder = Path.of(getConfigValue("images.destination", "{origpath}", vr));
        importFolder = Path.of(getConfigValue("targetFolder", "{importpath}", vr));
        pdfFolder = Path.of(getConfigValue("pagePdfs.destination", "{processpath}/ocr/{processtitle}_pdf", vr));
        textFolder = Path.of(getConfigValue("plaintext.destination", "{processpath}/ocr/{processtitle}_txt", vr));
        altoFolder = Path.of(getConfigValue("alto.destination", "{processpath}/ocr/{processtitle}_alto", vr));

        if (useS3) {
            tifFolder = Paths.get(tempFolder.toString(), tifFolder.getFileName().toString());
            importFolder = Paths.get(tempFolder.toString(), importFolder.getFileName().toString());
            pdfFolder = Paths.get(tempFolder.toString(), pdfFolder.getFileName().toString());
            textFolder = Paths.get(tempFolder.toString(), textFolder.getFileName().toString());
            altoFolder = Paths.get(tempFolder.toString(), altoFolder.getFileName().toString());
        } else {

        }

        Files.createDirectories(importFolder);
        if (shouldWriteImageFiles()) {
            if (overwriteOldData) {
                deleteFilesInFolder(tifFolder.toString(), NIOFileUtils.imageNameFilter);
            }
            Files.createDirectories(tifFolder);
        }
        if (shouldWriteSinglePagePdfs()) {
            if (overwriteOldData) {
                deleteFilesInFolder(pdfFolder.toString(), null);
            }
            Files.createDirectories(pdfFolder);
        }
        if (shouldWritePlainText()) {
            if (overwriteOldData) {
                deleteFilesInFolder(textFolder.toString(), null);
            }
            Files.createDirectories(textFolder);
        }
        if (shouldWriteAltoFiles()) {
            if (overwriteOldData) {
                deleteFilesInFolder(altoFolder.toString(), null);
            }
            Files.createDirectories(altoFolder);
        }

        Fileformat ff = origFileformat;
        DocStruct topStruct = getTopStruct(ff);
        DocStruct boundBook = ff.getDigitalDocument().getPhysicalDocStruct();
        int numExistingPages = boundBook.getAllChildren() == null ? 0 : boundBook.getAllChildren().size();

        if (numExistingPages > 0 && overwriteOldData) {
            removeAllFileReferences(ff.getDigitalDocument().getFileSet(), topStruct, boundBook);
            numExistingPages = 0;
        }

        MutableInt counter = new MutableInt(numExistingPages + 1);
        String pdfDocType = config.getString("mets.docType.parent", config.getString("docType.parent", ""));
        String childDocType = config.getString("mets.docType.children", config.getString("docType.children", ""));
        for (File file : importFiles) {
            if (StringUtils.isNotBlank(pdfDocType) && shouldWriteMetsFile()) {
                DocStruct ds = addDocStruct(topStruct, ff, prefs, pdfDocType, file);
                ff = convertPdf(file, ff, prefs, ds, childDocType, counter);
            } else {
                ff = convertPdf(file, ff, prefs, null, childDocType, counter);
            }
        }
        logger.debug("A total of " + (counter.intValue() - 1) + " pages have so far been converted");
        return ff;

    }

    /**
     * Set a file namer for the output files for the pdf converter
     */
    private void preparePDFConverter() {
        String naming = this.config.getString("fileNaming.strategy", "CONSECUTIVE_COUNT");
        switch (naming) {
            case "PDF_FILENAME":
                PDFConverter.setFileNamingStrategy(new PdfFilenameNamer("%03d"));
                break;
            case "CONSECUTIVE_COUNT":
            default:
                PDFConverter.setFileNamingStrategy(new NumberFormatNamer("%08d"));
        }
    }

    private void removeAllFileReferences(FileSet fs, DocStruct topStruct, DocStruct boundBook) throws PreferencesException {
        boundBook.getAllChildrenAsFlatList().forEach(p -> {
            new ArrayList<>(p.getAllFromReferences()).forEach(ref -> p.removeReferenceFrom(ref.getTarget()));
            boundBook.removeChild(p);
        });
        new ArrayList<>(topStruct.getAllToReferences()).forEach(r -> topStruct.removeReferenceTo(r.getTarget()));
        List<DocStruct> docStructs = topStruct.getAllChildrenAsFlatList();
        for (DocStruct ds : docStructs) {
            new ArrayList<>(ds.getAllToReferences()).forEach(r -> ds.removeReferenceTo(r.getTarget()));
        }
        new ArrayList<>(fs.getAllFiles()).forEach(f -> fs.removeFile(f));
    }

    private void deleteFilesInFolder(String folder, Filter<Path> fileFilter) throws IOException {
        List<Path> imageFiles;
        if (fileFilter != null) {
            imageFiles = StorageProvider.getInstance().listFiles(folder, fileFilter);
        } else {
            imageFiles = StorageProvider.getInstance().listFiles(folder);
        }
        if (imageFiles != null) {
            for (Path file : imageFiles) {
                StorageProvider.getInstance().deleteFile(file);
            }
        }
    }

    private DocStruct getTopStruct(Fileformat ff) throws PreferencesException {
        DocStruct top = ff.getDigitalDocument().getLogicalDocStruct();
        if (top.getType().isAnchor() && !top.getAllChildren().isEmpty()) {
            top = top.getAllChildren().get(0);
        }
        return top;
    }

    private DocStruct addDocStruct(DocStruct parent, Fileformat ff, Prefs prefs, String docTypeName, File file) throws UGHException {

        DocStructType dsType = prefs.getDocStrctTypeByName(docTypeName);
        DocStruct ds = ff.getDigitalDocument().createDocStruct(dsType);
        parent.addChild(ds);
        return ds;
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
     * @throws UGHException
     * @throws JDOMException
     */
    private Fileformat convertPdf(File importFile, Fileformat origFileformat, Prefs prefs, DocStruct parent, String childDocType, MutableInt counter)
            throws PDFReadException, PDFWriteException, IOException, UGHException {
        File importPdfFile = PDFConverter.decryptPdf(importFile, importFolder.toFile());
        if (importPdfFile == null || !importPdfFile.exists()) {
            importPdfFile = getImportPdfFile(importFile, false);
            if (!importPdfFile.equals(importFile)) {
                FileUtils.moveFile(importFile, importPdfFile);
            }
            logger.debug("Copied original PDF file to " + importPdfFile);
        } else {
            logger.debug("Created decrypted PDF file at " + importPdfFile);
        }
        reverter.addMovedPath(importFile, importPdfFile);

        int imageResolution = config.getInt("images.resolution", 300);
        String imageFormat = config.getString("images.format", "tif");

        List<File> textFiles = Collections.emptyList();
        if (shouldWritePlainText()) {
            try {
                textFiles = PDFConverter.writeFullText(importPdfFile, textFolder.toFile(), DEFAULT_ENCODING, counter.toInteger());
                reverter.addCreatedPaths(textFiles);
                logger.debug("Created " + textFiles.size() + " text files in " + textFolder);
            } catch (PDFReadException | PDFWriteException e) {
                String message = "Failed reading fulltext from pdf {1}: {2}".replace("{1}", importPdfFile.toString()).replace("{2}", e.toString());
                logger.warn(message);
                if (shouldFailOnPlaintextError()) {
                    throw e;
                } else {
                    writeLogEntry(LogType.WARN, message);
                    deleteFilesAndFolder(textFiles);
                }
            }
        }

        List<File> pdfFiles = Collections.emptyList();
        if (shouldWriteSinglePagePdfs()) {
            try {
                pdfFiles = PDFConverter.writeSinglePagePdfs(importPdfFile, pdfFolder.toFile(), counter.toInteger());
                reverter.addCreatedPaths(pdfFiles);
                logger.debug("Created " + pdfFiles.size() + " PDF files in " + pdfFolder);
            } catch (PDFReadException | PDFWriteException e) {
                String message =
                        "Failed extracting single page pdfs from pdf {1}: {2}".replace("{1}", importPdfFile.toString()).replace("{2}", e.toString());
                logger.warn(message);
                if (shouldFailOnSinglePagePdfError()) {
                    throw e;
                } else {
                    writeLogEntry(LogType.WARN, message);
                    deleteFilesAndFolder(pdfFiles);
                }
            }
        }

        List<File> imageFiles = Collections.emptyList();
        if (shouldWriteImageFiles()) {
            try {
                imageFiles =
                        PDFConverter.writeImages(importPdfFile, tifFolder.toFile(), counter.toInteger(), imageResolution, imageFormat,
                                getTempFolder(), getImageGenerationMethod(), getImageGenerationParams());
                reverter.addCreatedPaths(imageFiles);
                logger.debug("Created " + imageFiles.size() + " TIFF files in " + tifFolder);
            } catch (PDFWriteException e) {
                String message = "Failed extracting images from pdf {1}: {2}".replace("{1}", importPdfFile.toString()).replace("{2}", e.toString());
                logger.warn(message);
                if (shouldFailOnImagesError()) {
                    throw e;
                } else {
                    writeLogEntry(LogType.WARN, message);
                    deleteFilesAndFolder(imageFiles);
                }
            }
        }

        List<File> altoFiles = Collections.emptyList();
        if (shouldWriteAltoFiles()) {
            try {
                altoFiles = PDFConverter.writeAltoFiles(importPdfFile, altoFolder.toFile(), imageFiles, false, counter.toInteger());
                reverter.addCreatedPaths(altoFiles);
                logger.debug("Created " + altoFiles.size() + " ALTO files in " + altoFolder);
            } catch (PDFReadException | PDFWriteException e) {
                String message = "Failed writing alto files from pdf {1}: {2}".replace("{1}", importPdfFile.toString()).replace("{2}", e.toString());
                logger.warn(message);
                if (shouldFailOnAltoError()) {
                    throw new UGHException(e);
                } else {
                    writeLogEntry(LogType.WARN, message);
                    deleteFilesAndFolder(altoFiles);
                }
            } finally {
                if (!shouldWriteSinglePagePdfs()) {
                    //if single page pdf were only written to create alto files, delete them now
                    deleteFilesAndFolder(pdfFiles);
                }
            }
        }

        Fileformat ff;
        if (shouldWriteMetsFile()) {
            try {
                String childDocTypeToUse = getChildDocTypeToUse(childDocType, parent, origFileformat, prefs);
                ff = PDFConverter.writeFileformat(importPdfFile, imageFiles, origFileformat, prefs, counter.toInteger(), parent, childDocTypeToUse);
                logger.debug("Created Mets/Mods fileformat from PDF");
            } catch (Throwable e) {
                String message = "Failed writing mets file from pdf {1}: {2}".replace("{1}", importPdfFile.toString()).replace("{2}", e.toString());
                logger.warn(message);
                if (shouldFailOnMetsError()) {
                    throw e;
                } else {
                    writeLogEntry(LogType.WARN, message);
                    return origFileformat;
                }
            }
        } else {
            ff = origFileformat;
        }

        counter.add(Math.max(pdfFiles.size(), imageFiles.size()));

        return ff;
    }

    private String getChildDocTypeToUse(String childDocTypeName, DocStruct parent, Fileformat ff, Prefs prefs) {
        try {
            DocStruct parentToUse = parent == null ? ff.getDigitalDocument().getLogicalDocStruct() : parent;
            if (parentToUse.getType().isAnchor() && !parentToUse.getAllChildren().isEmpty()) {
                parentToUse = parentToUse.getAllChildren().get(0);
            }
            DocStructType childDocType = prefs.getDocStrctTypeByName(childDocTypeName);
            if (parentToUse.isDocStructTypeAllowedAsChild(childDocType)) {
                return childDocType.getName();
            } else {
                return "";
            }
        } catch (PreferencesException e) {
            logger.debug(
                    "Cannot find suitable docStruct for preferred type %s and parent %s: %s".formatted(childDocTypeName,
                            Optional.ofNullable(parent).map(DocStruct::getType).map(DocStructType::getName).orElse("unknown"),
                            e.toString()));
            return "";
        }
    }

    private File getImportPdfFile(File importFile, boolean createBackups) {
        File importPdfFile;
        importPdfFile = new File(importFolder.toFile(), importFile.getName());
        if (createBackups) {
            int index = 1;
            while (importPdfFile.exists()) {
                String baseName = FilenameUtils.getBaseName(importFile.getName());
                String extension = FilenameUtils.getExtension(importFile.getName());
                String filename = baseName + "_" + index + "." + extension;
                importPdfFile = new File(importFolder.toFile(), filename);
            }
        }
        return importPdfFile;
    }

    private void deleteFilesAndFolder(List<File> files) {
        if (!files.isEmpty()) {
            for (File file : files) {
                file.delete();
            }
            File folder = files.get(0).getParentFile();
            String[] content = folder.list();
            if (content != null && content.length == 0) {
                folder.delete();
            }
        }
    }

    private void writeLogEntry(LogType type, String message) {
        Helper.addMessageToProcessJournal(step.getProzess().getId(), type, message, "automatic");
    }

    private boolean shouldFailOnAltoError() {
        return config.getBoolean("alto.failOnError", true);
    }

    private boolean shouldFailOnImagesError() {
        return config.getBoolean("images.failOnError", true);

    }

    private boolean shouldFailOnSinglePagePdfError() {
        return config.getBoolean("pagePdfs.failOnError", true);

    }

    private boolean shouldFailOnPlaintextError() {
        return config.getBoolean("plaintext.failOnError", true);
    }

    private boolean shouldFailOnMetsError() {
        return config.getBoolean("mets.failOnError", true);

    }

    private boolean shouldWriteAltoFiles() {
        return config.getBoolean("alto.write", true);
    }

    private boolean shouldWriteImageFiles() {
        return config.getBoolean("images.write", true);
    }

    private boolean shouldWriteSinglePagePdfs() {
        return config.getBoolean("pagePdfs.write", true);
    }

    private boolean shouldWritePlainText() {
        return config.getBoolean("plaintext.write", true);
    }

    private boolean shouldWriteMetsFile() {
        return config.getBoolean("mets.write", true);

    }

    protected Configuration getConfig(String projectName, String stepName) throws PluginConfigurationException {
        XMLConfiguration baseConfig = ConfigPlugins.getPluginConfig(this.getTitle());
        if ("config".equals(baseConfig.getRootElementName())) {
            return baseConfig;
        } else {
            //multiple configurations
            ExpressionEngine origEngine = baseConfig.getExpressionEngine();
            baseConfig.setExpressionEngine(new XPathExpressionEngine());

            SubnodeConfiguration myconfig = null;

            // order of configuration is:
            // 1.) project name and step name matches
            // 2.) step name matches and project is *
            // 3.) project name matches and step name is *
            // 4.) project name and step name are *
            try {
                myconfig = baseConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '" + stepName + "']");
            } catch (IllegalArgumentException e) {
                try {
                    myconfig = baseConfig.configurationAt("//config[./project = '*'][./step = '" + stepName + "']");
                } catch (IllegalArgumentException e1) {
                    try {
                        myconfig = baseConfig.configurationAt("//config[./project = '" + projectName + "'][./step = '*']");
                    } catch (IllegalArgumentException e2) {
                        try {
                            myconfig = baseConfig.configurationAt("//config[./project = '*'][./step = '*']");
                        } catch (IllegalArgumentException e3) {
                            throw new PluginConfigurationException("Error reading config file " + baseConfig.getFileName(), e3);
                        }
                    }
                }
            }
            baseConfig.setExpressionEngine(origEngine);
            if (myconfig == null) {
                return baseConfig;
            } else {
                myconfig.setExpressionEngine(origEngine);
                return myconfig;
            }
        }

    }

    private File getTempFolder() throws IOException {
        String folderpath = ConfigurationHelper.getInstance().getTemporaryFolder();
        if (StringUtils.isNotBlank(folderpath)) {
            return new File(folderpath);
        } else {
            return Files.createTempDirectory("pdf_extraction_").toFile();
        }
    }

    private String getImageGenerationMethod() {
        return this.config.getString("images.generator", "ghostscript");
    }

    private String[] getImageGenerationParams() {
        return this.config.getStringArray("images.generatorParameter");
    }
}
