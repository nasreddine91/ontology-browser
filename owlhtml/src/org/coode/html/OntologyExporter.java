package org.coode.html;

import org.coode.html.impl.OWLHTMLConstants;
import org.coode.html.impl.OWLHTMLKitImpl;
import org.coode.html.impl.OWLHTMLProperty;
import org.coode.html.index.OWLContentsHTMLPage;
import org.coode.html.index.OWLObjectIndexHTMLPage;
import org.coode.html.page.EmptyOWLDocPage;
import org.coode.html.summary.*;
import org.coode.html.url.URLScheme;
import org.coode.html.util.URLUtils;
import org.coode.html.util.FileUtils;
import org.coode.html.hierarchy.OWLClassHierarchyTreeFragment;
import org.coode.html.hierarchy.OWLPropertyHierarchyTreeFragment;
import org.coode.html.doclet.HierarchyRootDoclet;
import org.coode.owl.mngr.NamedObjectType;
import org.coode.owl.mngr.ServerConstants;
import org.coode.owl.mngr.ServerProperty;
import org.semanticweb.owlapi.model.*;
import org.apache.log4j.Logger;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Author: Nick Drummond<br>
 * nick.drummond@cs.manchester.ac.uk<br>
 * http://www.cs.man.ac.uk/~drummond<br><br>
 * <p/>
 * The University Of Manchester<br>
 * Bio Health Informatics Group<br>
 * Date: Jun 11, 2007<br><br>
 * <p/>
 * code made available under Mozilla Public License (http://www.mozilla.org/MPL/MPL-1.1.html)<br>
 * copyright 2006, The University of Manchester<br>
 *
 * Class that creates a set of static HTML pages in the directory given.
 * OWL entity subfolders are created for classes/properties/individuals/ontologies
 * Index pages are created for all entity folders (and overall)
 * A contents page is created
 */
public class OntologyExporter {

    private static Logger logger = Logger.getLogger(OntologyExporter.class);

    private OWLHTMLKit kit;

    private File root;

    private OWLObjectIndexHTMLPage indexAllResourcesRenderer;

    private Map<NamedObjectType, OWLObjectIndexHTMLPage> typeIndices = new HashMap<NamedObjectType, OWLObjectIndexHTMLPage>();

    private final FileUtils fileUtils;


    public static void main(String[] args) {
        try {
            // the default base should never be exposed in the output because all URLs should be relative
            URL defaultBase = new URL("http://www.co-ode.org/ontologies/owldoc/");

            OWLHTMLKit kit = new OWLHTMLKitImpl("kit", defaultBase);

            String out = null;

            for (String arg : args){
                String[] argPair = arg.split("=");
                if (argPair.length == 1){
                    if (argPair[0].equals("-t")){
                        logger.info("Switching mini hierarchies on");
                        kit.getHTMLProperties().setBoolean(OWLHTMLProperty.optionShowMiniHierarchies, true);
                    }
                    else if (argPair[0].equals("-l")){
                        logger.info("Rendering labels");
                        kit.getOWLServer().getProperties().set(ServerProperty.optionRenderer, ServerConstants.RENDERER_LABEL);
                    }
                    else if (argPair[0].equals("-c")){
                        logger.info("Switching ontology summary cloud on");
                        kit.getHTMLProperties().setBoolean(OWLHTMLProperty.optionRenderOntologySummaryCloud, true);
                    }
                    else{
                        // must be an ontology file
                        URI ontLoc = new File(argPair[0]).toURI();
                        logger.info("Loading ontology: " + ontLoc);
                        kit.getOWLServer().loadOntology(ontLoc);
                    }
                }
                else{
                    if (argPair[0].equals("out")){
                        out = argPair[1];
                    }
                }
            }

            if (out == null){
                out = "owldoc";
            }
            File outputDirectory = new File(out);
            if (!outputDirectory.exists()){
                outputDirectory.mkdir();
            }

            if (!outputDirectory.isDirectory()){
                logger.fatal("The output location specified is not a directory");
                System.exit(1);
            }
            if (kit.getOWLServer().getOntologies().isEmpty()){
                logger.fatal("No ontologies loaded!");
                System.exit(1);
            }

            OntologyExporter exporter = new OntologyExporter(kit);

            logger.info("Compiling owldoc into directory: " + outputDirectory);

            exporter.export(outputDirectory);

            logger.info("Done!");

            kit.dispose();
        }
        catch (Exception e) {
            logger.error("Problem exporting OWLDoc", e);
        }

        System.exit(0);
    }


    public OntologyExporter(OWLHTMLKit kit) {
        this.kit = kit;
        this.fileUtils = new FileUtils("resources/", OWLHTMLConstants.DEFAULT_ENCODING);
    }

    public File export(File root) throws Exception {
        if (root.isDirectory()){
            this.root = root;

            copyResource(kit.getHTMLProperties().get(OWLHTMLProperty.optionDefaultCSS), root);
            copyResource(OWLHTMLConstants.JS_TREE, root);

            // initialise the all resources index
            indexAllResourcesRenderer = new OWLObjectIndexHTMLPage(kit);
            indexAllResourcesRenderer.setTitle(NamedObjectType.entities.getPluralRendering());

            // step through the ontologies
            for (OWLOntology ont : kit.getVisibleOntologies()){
                exportOntology(ont);
            }

            createIndices();

            createAllResourcesIndex();

            createContents();

            return createHTMLFrames();
        }
        else{
            throw new Exception("Root is not a directory: " + root);
        }
    }


    private void copyResource(String resourceName, File root) throws IOException {
        logger.debug("copying... " + resourceName);
        InputStream in = kit.getClass().getClassLoader().getResourceAsStream("resources/" + resourceName);
        File out = new File(root, resourceName);
        fileUtils.saveFile(in, out);
    }


    private void exportOntology(OWLOntology ont) throws IOException {

        // export the ontology summary
        OWLOntologySummaryHTMLPage ontologySummary = new OWLOntologySummaryHTMLPage(kit);
        logger.debug("exporting ontology: " + ont);
        ontologySummary.setUserObject(ont);

        URL pageURL = kit.getURLScheme().getURLForOWLObject(ont);
        String localFilename = URLUtils.createRelativeURL(kit.getBaseURL(), pageURL);
        File ontologySummaryFile = new File(root, localFilename);
        ensureExists(ontologySummaryFile);

        PrintWriter out = fileUtils.open(ontologySummaryFile);
        ontologySummary.renderAll(pageURL, out);

        out.flush();
        out.close();

        OWLObjectIndexHTMLPage ontologyIndexRenderer = getTypeIndexRenderer(NamedObjectType.ontologies);
        ontologyIndexRenderer.add(ont);

        exportReferencedEntities(ont);
    }

    private void exportReferencedEntities(OWLOntology ont) throws IOException {

        final OWLClassSummaryHTMLPage clsSummary = new OWLClassSummaryHTMLPage(kit);
        final OWLObjectPropertySummaryHTMLPage objPropSummary = new OWLObjectPropertySummaryHTMLPage(kit);
        final OWLDataPropertySummaryHTMLPage dataPropSummary = new OWLDataPropertySummaryHTMLPage(kit);
        final OWLAnnotationPropertySummaryHTMLPage annotationPropSummary = new OWLAnnotationPropertySummaryHTMLPage(kit);
        final OWLIndividualSummaryHTMLPage indSummary = new OWLIndividualSummaryHTMLPage(kit);
        final OWLDatatypeSummaryHTMLPage datatypeSummary = new OWLDatatypeSummaryHTMLPage(kit);

        if (kit.getHTMLProperties().isSet(OWLHTMLProperty.optionShowMiniHierarchies)){
            final OWLClassHierarchyTreeFragment clsTreeModel = new OWLClassHierarchyTreeFragment(kit, kit.getOWLServer().getClassHierarchyProvider(), "Asserted Class Hierarchy");
            clsSummary.setOWLHierarchyRenderer(new HierarchyRootDoclet<OWLClass>(kit, clsTreeModel));

            final OWLPropertyHierarchyTreeFragment<OWLObjectProperty> objPropTreeModel = new OWLPropertyHierarchyTreeFragment<OWLObjectProperty>(kit, kit.getOWLServer().getOWLObjectPropertyHierarchyProvider());
            objPropSummary.setOWLHierarchyRenderer(new HierarchyRootDoclet<OWLObjectProperty>(kit, objPropTreeModel));

            final OWLPropertyHierarchyTreeFragment<OWLDataProperty> dataPropTreeModel = new OWLPropertyHierarchyTreeFragment<OWLDataProperty>(kit, kit.getOWLServer().getOWLDataPropertyHierarchyProvider());
            dataPropSummary.setOWLHierarchyRenderer(new HierarchyRootDoclet<OWLDataProperty>(kit, dataPropTreeModel));
        }

        // export classes
        exportReferencedEntities(NamedObjectType.classes,
                                 clsSummary,
                                 getReferencedClasses(ont),
                                 ont);

        // export object properties
        exportReferencedEntities(NamedObjectType.objectproperties,
                                 objPropSummary,
                                 ont.getReferencedObjectProperties(),
                                 ont);

        // export data properties
        exportReferencedEntities(NamedObjectType.dataproperties,
                                 dataPropSummary,
                                 ont.getReferencedDataProperties(),
                                 ont);

        // export annotation properties
        exportReferencedEntities(NamedObjectType.annotationproperties,
                                 annotationPropSummary,
                                 ont.getReferencedAnnotationProperties(),
                                 ont);

        // export individuals
        exportReferencedEntities(NamedObjectType.individuals,
                                 indSummary,
                                 ont.getReferencedIndividuals(),
                                 ont);


        // export datatypes
        exportReferencedEntities(NamedObjectType.datatypes,
                                 datatypeSummary,
                                 ont.getReferencedDatatypes(),
                                 ont);
    }

    private void exportReferencedEntities(NamedObjectType type, EmptyOWLDocPage ren,
                                          Set<? extends OWLEntity>entities, OWLOntology ont) throws IOException {
        if (!entities.isEmpty()){

//            File subDirFile = new File(root, type.toString());
//            if (!subDirFile.exists()){
//                subDirFile.mkdir();
//            }

            // index generation for current ontology
            final URLScheme urlScheme = kit.getURLScheme();

            URL indexBaseURL = urlScheme.getURLForOntologyIndex(ont, type);
            String localFilename = URLUtils.createRelativeURL(kit.getBaseURL(), indexBaseURL);
            File ontologyEntityIndexFile = new File(root, localFilename);
            ensureExists(ontologyEntityIndexFile);
            PrintWriter indexWriter = fileUtils.open(ontologyEntityIndexFile);
            OWLObjectIndexHTMLPage ontIndexRenderer = new OWLObjectIndexHTMLPage(kit);
            ontIndexRenderer.setTitle(kit.getOWLServer().getOntologyShortFormProvider().getShortForm(ont) + ": " + type);

            // index generation for all ontologies
            OWLObjectIndexHTMLPage indexAllRenderer = getTypeIndexRenderer(type);

            for (OWLEntity entity : entities){

                // add to ontology index
                ontIndexRenderer.add(entity);

                if (exportEntity(entity, ren)){

                    // add to all index
                    indexAllRenderer.add(entity);

                    // add to all resources index
                    indexAllResourcesRenderer.add(entity);
                }
            }

            ontIndexRenderer.renderAll(indexBaseURL, indexWriter);
            indexWriter.flush();
            indexWriter.close();
        }
    }

    private boolean exportEntity(OWLEntity entity, EmptyOWLDocPage ren) throws IOException {

        URL entityURL = kit.getURLScheme().getURLForOWLObject(entity);
        String localFilename = URLUtils.createRelativeURL(kit.getBaseURL(), entityURL);
        File entitySummaryFile = new File(root, localFilename);

        if (entitySummaryFile.exists()){ // already generated this entity summary
            return false;
        }

        ensureExists(entitySummaryFile);
        Writer fileWriter = new FileWriter(entitySummaryFile);
        PrintWriter writer = new PrintWriter(fileWriter);

        ren.setUserObject(entity);
        ren.renderAll(entityURL, writer);

        fileWriter.close();
        return true;
    }

    private void ensureExists(File f) throws IOException {
        if (!f.getParentFile().exists()){
            f.getParentFile().mkdirs();
        }
        if (!f.exists()){
            f.createNewFile();
        }
    }

    private OWLObjectIndexHTMLPage getTypeIndexRenderer(NamedObjectType type) throws MalformedURLException {
        OWLObjectIndexHTMLPage indexAllRenderer = typeIndices.get(type);
        if (indexAllRenderer == null){
            indexAllRenderer = new OWLObjectIndexHTMLPage(kit);
            indexAllRenderer.setTitle("All " +
                                      type.toString().substring(0, 1).toUpperCase() +
                                      type.toString().substring(1).toLowerCase());
            typeIndices.put(type, indexAllRenderer);
        }
        return indexAllRenderer;
    }

    private void exportHTMLFrames(PrintWriter out) {

        final URL ontURL = kit.getURLScheme().getURLForOWLObject(kit.getOWLServer().getActiveOntology());
        String activeOntologyPage = URLUtils.createRelativeURL(kit.getBaseURL(), ontURL);

        out.print("<html>\n" +
                  "<head>\n" +
                  "<title>\n" +
                  "OWLDoc\n" +
                  "</title>\n" +
                  "</head>\n" +
                  "<frameset cols=\"30%,70%\">\n" +
                  "    <frameset rows=\"30%,70%\">\n");
        out.print("<frame src=\"" + OWLHTMLConstants.CONTENTS_HTML + "\" name=\"nav\" title=\"Contents\"/>\n");
        out.print("<frame src=\"" + kit.getHTMLProperties().get(OWLHTMLProperty.optionIndexAllURL) + "\" name=\"subnav\" title=\"Index\"/>\n");
        out.print("    </frameset>\n");
        out.print("    <frame src=\"" + activeOntologyPage + "\" name=\"content\" title=\"Content\"/>\n");
        out.print("    <noframes>classes/index.html</noframes>\n" +
                  "</frameset>\n" +
                  "</html>");
    }

    private void createIndices() throws IOException {
        for (NamedObjectType type : typeIndices.keySet()){
            PrintWriter writer = fileUtils.open(new File(root, type + "/" + OWLHTMLConstants.INDEX_HTML));
            URL pageURL = kit.getURLScheme().getURLForIndex(type);
            typeIndices.get(type).renderAll(pageURL, writer);
            writer.flush();
            writer.close();
        }
    }

    private void createAllResourcesIndex() throws IOException {
        // by this point we've accumulated the resources into the renderer
        String indexFile = kit.getHTMLProperties().get(OWLHTMLProperty.optionIndexAllURL);
        PrintWriter indexAllWriter = fileUtils.open(new File(root, indexFile));
        URL pageURL = kit.getURLScheme().getURLForRelativePage(indexFile);
        indexAllResourcesRenderer.renderAll(pageURL, indexAllWriter);
        indexAllWriter.flush();
        indexAllWriter.close();
    }

    private void createContents() throws IOException {
        OWLContentsHTMLPage contentsRenderer = new OWLContentsHTMLPage(kit);
        PrintWriter contentsWriter = fileUtils.open(new File(root, OWLHTMLConstants.CONTENTS_HTML));
        URL pageURL = kit.getURLScheme().getURLForRelativePage(OWLHTMLConstants.CONTENTS_HTML);
        contentsRenderer.renderAll(pageURL, contentsWriter);
        contentsWriter.flush();
        contentsWriter.close();
    }

    private File createHTMLFrames() throws IOException {
        final File indexFile = new File(root, OWLHTMLConstants.INDEX_HTML);
        PrintWriter htmlFramesWriter = fileUtils.open(indexFile);
        exportHTMLFrames(htmlFramesWriter);
        htmlFramesWriter.flush();
        htmlFramesWriter.close();
        return indexFile;
    }


    // get all classes (including owl:Thing)
    private Set<OWLClass> getReferencedClasses(OWLOntology ont) {
        Set<OWLClass> referencedClasses = new HashSet<OWLClass>();
        referencedClasses.addAll(ont.getReferencedClasses());
        final OWLDataFactory df = kit.getOWLServer().getOWLOntologyManager().getOWLDataFactory();
        referencedClasses.add(df.getOWLThing());
        referencedClasses.add(df.getOWLNothing());
        return referencedClasses;
    }
}