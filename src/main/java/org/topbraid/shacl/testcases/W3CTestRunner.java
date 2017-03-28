package org.topbraid.shacl.testcases;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.compose.MultiUnion;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFList;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.util.FileUtils;
import org.apache.jena.vocabulary.RDF;
import org.apache.jena.vocabulary.RDFS;
import org.topbraid.shacl.arq.SHACLPaths;
import org.topbraid.shacl.validation.ShapesGraph;
import org.topbraid.shacl.validation.ValidationEngine;
import org.topbraid.shacl.validation.ValidationEngineFactory;
import org.topbraid.shacl.validation.predicates.ExcludeMetaShapesPredicate;
import org.topbraid.shacl.vocabulary.DASH;
import org.topbraid.shacl.vocabulary.MF;
import org.topbraid.shacl.vocabulary.SH;
import org.topbraid.shacl.vocabulary.SHT;
import org.topbraid.shacl.vocabulary.TOSH;
import org.topbraid.spin.arq.ARQFactory;
import org.topbraid.spin.util.JenaUtil;

/**
 * Helper object for executing the W3C test cases for SHACL.
 * The tests are assumed to be in a folder structure mirroring
 * 
 * https://github.com/w3c/data-shapes/tree/gh-pages/data-shapes-test-suite/tests
 * 
 * @author Holger Knublauch
 */
public class W3CTestRunner {
	
	private List<Item> items = new LinkedList<>();
	
	
	public W3CTestRunner(File rootManifest) throws IOException {
		collectItems(rootManifest, "urn:root/");
	}
	
	
	private void collectItems(File manifestFile, String baseURI) throws IOException {
		
		Model model = JenaUtil.createMemoryModel();
		model.read(new FileInputStream(manifestFile), baseURI, FileUtils.langTurtle);
		
		for(Resource manifest : model.listSubjectsWithProperty(RDF.type, MF.Manifest).toList()) {
			for(Resource include : JenaUtil.getResourceProperties(manifest, MF.include)) {
				String path = include.getURI().substring(baseURI.length());
				File includeFile = new File(manifestFile.getParentFile(), path);
				if(path.contains("/")) {
					String addURI = path.substring(0, path.indexOf('/'));
					collectItems(includeFile, baseURI + addURI + "/");
				}
				else {
					collectItems(includeFile, baseURI + path);
				}
			}
			for(Resource entries : JenaUtil.getResourceProperties(manifest, MF.entries)) {
				for(RDFNode entry : entries.as(RDFList.class).iterator().toList()) {
					items.add(new Item(entry.asResource()));
				}
			}
		}
	}
	
	
	public List<Item> getItems() {
		return items;
	}
	
	
	public void run(PrintStream out) throws InterruptedException {
		long startTime = System.currentTimeMillis();
		out.println("Running " + items.size() + " W3C Test Cases...");
		int count = 0;
		for(Item item : items) {
			if(!item.run(out)) {
				count++;
			}
		}
		out.println("Completed: " + count + " test failures (Duration: " + (System.currentTimeMillis() - startTime) + " ms)");
	}
	
	
	public class Item {
		
		// The sht:Validate in its defining Model
		Resource entry;
		
		Item(Resource entry) {
			this.entry = entry;
		}
		
		
		public boolean run(PrintStream out) throws InterruptedException {
			Resource action = entry.getPropertyResourceValue(MF.action);
			Resource dataGraph = action.getPropertyResourceValue(SHT.dataGraph);
			Resource shapesGraphResource = action.getPropertyResourceValue(SHT.shapesGraph);
			
			Graph shapesBaseGraph = entry.getModel().getGraph();
			MultiUnion multiUnion = new MultiUnion(new Graph[] {
				shapesBaseGraph,
				ARQFactory.getNamedModel(TOSH.BASE_URI).getGraph(),
				ARQFactory.getNamedModel(DASH.BASE_URI).getGraph(),
				ARQFactory.getNamedModel(SH.BASE_URI).getGraph()
			});
			Model shapesModel = ModelFactory.createModelForGraph(multiUnion);
			
			Model dataModel = entry.getModel();

			URI shapesGraphURI = URI.create("urn:x-shacl-shapes-graph:" + UUID.randomUUID().toString());
			Dataset dataset = ARQFactory.get().getDataset(dataModel);
			dataset.addNamedModel(shapesGraphURI.toString(), shapesModel);

			ShapesGraph shapesGraph = new ShapesGraph(shapesModel, new ExcludeMetaShapesPredicate());
			ValidationEngine engine = ValidationEngineFactory.get().create(dataset, shapesGraphURI, shapesGraph, null);
			try {
				Resource actualReport = engine.validateAll();
				Model actualResults = actualReport.getModel();
				actualResults.setNsPrefix(SH.PREFIX, SH.NS);
				actualResults.setNsPrefix("rdf", RDF.getURI());
				actualResults.setNsPrefix("rdfs", RDFS.getURI());
				for(Property ignoredProperty : GraphValidationTestCaseType.IGNORED_PROPERTIES) {
					actualResults.removeAll(null, ignoredProperty, (RDFNode)null);
				}
				Model expectedModel = JenaUtil.createDefaultModel();
				Resource expectedReport = entry.getPropertyResourceValue(MF.result);
				for(Statement s : expectedReport.listProperties().toList()) {
					expectedModel.add(s);
				}
				for(Statement s : expectedReport.listProperties(SH.result).toList()) {
					for(Statement t : s.getResource().listProperties().toList()) {
						if(t.getPredicate().equals(DASH.suggestion)) {
							GraphValidationTestCaseType.addStatements(expectedModel, t);
						}
						else if(SH.resultPath.equals(t.getPredicate())) {
							expectedModel.add(t.getSubject(), t.getPredicate(),
									SHACLPaths.clonePath(t.getResource(), expectedModel));
						}
						else {
							expectedModel.add(t);
						}
					}
				}
				if(expectedModel.getGraph().isIsomorphicWith(actualResults.getGraph())) {
					out.println("PASSED: " + entry);
					return true;
				}
				else {
					out.println("FAILED: " + entry);
					return false;
				}
			}
			catch(Exception ex) {
				out.println("EXCEPTION: " + entry + " " + ex.getMessage());
				return false;
			}
		}
	}
}