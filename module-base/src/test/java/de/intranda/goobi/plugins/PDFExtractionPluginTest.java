package de.intranda.goobi.plugins;


import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import ugh.dl.DocStruct;
import ugh.dl.Fileformat;
import ugh.dl.Prefs;
import ugh.dl.Reference;
import ugh.exceptions.PreferencesException;
import ugh.exceptions.ReadException;
import ugh.exceptions.WriteException;
import ugh.fileformats.mets.MetsMods;

public class PDFExtractionPluginTest {

    Path ruleset = Paths.get("src/test/resources/ruleset.xml");
    Path metadata = Paths.get("src/test/resources/meta.xml");
    Path metadataOuput = Paths.get("src/test/resources/meta_out.xml");
    Prefs prefs = new Prefs();

    @Before
    public void setup() {
        System.out.println(ruleset.toAbsolutePath());
        try {
            assertTrue(prefs.loadPrefs(ruleset.toAbsolutePath().toString()));
        } catch (PreferencesException e) {
            e.printStackTrace();
        }        
    }
    
    @Test
    public void test() throws PreferencesException, ReadException, WriteException {

        Fileformat mm = new MetsMods(prefs);
        mm.read(metadata.toAbsolutePath().toString());

        List<DocStruct> pages = mm.getDigitalDocument().getPhysicalDocStruct().getAllChildrenAsFlatList();
        DocStruct monograph = mm.getDigitalDocument().getLogicalDocStruct();
        assertFalse(monograph.getAllToReferences().size() == 0);
        for (DocStruct page : pages) {
            List<Reference> froms = page.getAllFromReferences();
            List<Reference> tos = page.getAllToReferences();
            assertFalse(froms.size() == 0);
            assertTrue(tos.size() == 0);
        }
        
        new ArrayList<>(monograph.getAllToReferences()).forEach(r -> monograph.removeReferenceTo(r.getTarget()));
        List<DocStruct> docStructs = monograph.getAllChildrenAsFlatList();
        for (DocStruct ds : docStructs) {            
            new ArrayList<>(ds.getAllToReferences()).forEach(r -> ds.removeReferenceTo(r.getTarget()));
        }
        for (DocStruct page : pages) {
            new ArrayList<>(page.getAllFromReferences()).forEach(ref ->page.removeReferenceFrom(ref.getTarget()));
        }
        
        assertTrue(monograph.getAllToReferences().size() == 0);
        assertTrue(monograph.getAllChildren().get(0).getAllToReferences().size() == 0);
        for (DocStruct page : pages) {
            List<Reference> froms = page.getAllFromReferences();
            List<Reference> tos = page.getAllToReferences();
            assertTrue(froms.size() == 0);
            assertTrue(tos.size() == 0);
        }
        mm.write(metadataOuput.toAbsolutePath().toString());
        
    }

    private String getPageNo(DocStruct page) {
        return page.getAllMetadataByType(prefs.getMetadataTypeByName("physPageNumber")).get(0).getValue();
    }

}
