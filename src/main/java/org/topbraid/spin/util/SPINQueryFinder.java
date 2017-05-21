/*******************************************************************************
 * Copyright (c) 2009 TopQuadrant, Inc.
 * All rights reserved. 
 *******************************************************************************/
package org.topbraid.spin.util;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.apache.jena.query.Query;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.update.UpdateRequest;
import org.topbraid.spin.arq.ARQFactory;
import org.topbraid.spin.model.Argument;
import org.topbraid.spin.model.Ask;
import org.topbraid.spin.model.Command;
import org.topbraid.spin.model.CommandWithWhere;
import org.topbraid.spin.model.Construct;
import org.topbraid.spin.model.SPINFactory;
import org.topbraid.spin.model.Template;
import org.topbraid.spin.model.TemplateCall;
import org.topbraid.spin.model.update.Update;
import org.topbraid.spin.system.SPINLabels;
import org.topbraid.spin.vocabulary.SPIN;


/**
 * Can be used to search for all queries associated with a class, e.g. via spin:rule.
 * 
 * As of July 2014 this also includes "inherited" templates: for any template call, it will
 * walk up the class hierarchy of templaces and checks for each spin:body whether all
 * required arguments are present, then includes them.  Template calls that do not have all
 * non-optional arguments filled in will not be returned.
 *
 * @author Holger Knublauch
 */
public class SPINQueryFinder {
	

	public static void add(Map<Resource, List<CommandWrapper>> class2Query, Statement s,
			Model model, boolean withClass, boolean allowAsk) {

		if(!s.getObject().isResource()) {
			return;
		}
		
		String spinQueryText = null;
		String label = null;
		TemplateCall templateCall = SPINFactory.asTemplateCall(s.getResource());
		if(templateCall != null) {
			Template baseTemplate = templateCall.getTemplate();
			if(baseTemplate != null) {
				for(Resource superClass : JenaUtil.getAllSuperClassesStar(baseTemplate)) {
					if(JenaUtil.hasIndirectType(superClass, SPIN.Template)) {
						Template template = SPINFactory.asTemplate(superClass);
						Command body = template.getBody();
						Command spinCommand = null;
						if(body instanceof Construct || (allowAsk && body instanceof Ask)) {
							spinCommand = body;
						}
						else if(body instanceof Update) {
							spinCommand = body;
						}
						if(spinCommand != null) {
							spinQueryText = SPINLabels.get().getLabel(templateCall);
							label = spinQueryText;
							CommandWrapper wrapper = createCommandWrapper(class2Query, s, withClass, allowAsk, spinQueryText, label,
									spinCommand, templateCall);
							if(wrapper != null) {
								Map<String,RDFNode> bindings = templateCall.getArgumentsMapByVarNames();
								if(hasAllNonOptionalArguments(template, bindings)) {
									if(!bindings.isEmpty()) {
										wrapper.setTemplateBinding(bindings);
									}
									addCommandWrapper(class2Query, s, wrapper);
								}
							}
						}
					}
				}
			}
		}
		else {
			Command spinCommand = SPINFactory.asCommand(s.getResource());
			if(spinCommand != null) {
				label = spinCommand.getComment();
				CommandWrapper wrapper = createCommandWrapper(class2Query, s, withClass, allowAsk, spinQueryText, label,
						spinCommand, spinCommand);
				if(wrapper != null) {
					addCommandWrapper(class2Query, s, wrapper);
				}
			}
		}
	}


	/**
	 * Inserts a CommandWrapper into a given Map, creating a List if necessary.
	 * @param class2Query  the target Map to insert the CommandWrapper into
	 * @param s  the Statement (for the context resource)
	 * @param wrapper  the CommandWrapper to add
	 */
	private static void addCommandWrapper(
			Map<Resource, List<CommandWrapper>> class2Query, Statement s,
			CommandWrapper wrapper) {
		Resource type = s.getSubject();
		List<CommandWrapper> list = class2Query.get(type);
		if(list == null) {
			list = new LinkedList<CommandWrapper>(); 
			class2Query.put(type, list);
		}
		list.add(wrapper);
	}


	private static CommandWrapper createCommandWrapper(
			Map<Resource, List<CommandWrapper>> class2Query, Statement s,
			boolean withClass, boolean allowAsk, String spinQueryText,
			String label, Command spinCommand,
			Resource source) {
		String queryString = ARQFactory.get().createCommandString(spinCommand);
		boolean thisUnbound = spinCommand.hasProperty(SPIN.thisUnbound, JenaDatatypes.TRUE);
		if(spinQueryText == null) {
			spinQueryText = queryString;
		}
		CommandWrapper wrapper = null;
		if(spinCommand instanceof org.topbraid.spin.model.Query) {
			Query arqQuery = ARQFactory.get().createQuery(queryString);
			if(arqQuery.isConstructType() || (allowAsk && arqQuery.isAskType())) {
				boolean thisDeep = NestedQueries.hasNestedBlocksUsingThis(arqQuery.getQueryPattern());
				if(!thisUnbound && withClass && !thisDeep && SPINUtil.containsThis((CommandWithWhere)spinCommand)) {
					queryString = SPINUtil.addThisTypeClause(queryString);
					arqQuery = ARQFactory.get().createQuery(queryString);
				}
				wrapper = new QueryWrapper(arqQuery, source, spinQueryText, (org.topbraid.spin.model.Query)spinCommand, label, s, thisUnbound, thisDeep);
			}
		}
		else if(spinCommand instanceof Update) {
			UpdateRequest updateRequest = ARQFactory.get().createUpdateRequest(queryString);
			org.apache.jena.update.Update operation = updateRequest.getOperations().get(0);
			boolean thisDeep = NestedQueries.hasNestedBlocksUsingThis(operation);
			if(!thisUnbound && withClass && !thisDeep && SPINUtil.containsThis((CommandWithWhere)spinCommand)) {
				queryString = SPINUtil.addThisTypeClause(queryString);
				updateRequest = ARQFactory.get().createUpdateRequest(queryString);
				operation = updateRequest.getOperations().get(0);
			}
			wrapper = new UpdateWrapper(operation, source, spinQueryText, (Update)spinCommand, label, s, thisUnbound, thisDeep);
		}
		return wrapper;
	}
	
	
	public static Map<Resource, List<CommandWrapper>> getClass2QueryMap(Model model, Model queryModel, Property predicate, boolean withClass, boolean allowAsk) {
		return getClass2QueryMap(model, queryModel, predicate, withClass, allowAsk, null);
	}
	
	
	/**
	 * Gets a Map of QueryWrappers with their associated classes. 
	 * @param model  the Model to operate on
	 * @param queryModel  the Model to query on (might be different)
	 * @param predicate  the predicate such as <code>spin:rule</code>
	 * @param withClass  true to also include a SPARQL clause to bind ?this
	 *                   (something along the lines of ?this a ?THIS_CLASS) 
	 * @param allowAsk  also return ASK queries
	 * @param filter  an optional Predicate that must return true for any matching resource
	 * @return the result Map, possibly empty but not null
	 */
	public static Map<Resource, List<CommandWrapper>> getClass2QueryMap(Model model, Model queryModel, Property predicate, boolean withClass, boolean allowAsk, Predicate<Resource> filter) {
		predicate = model.getProperty(predicate.getURI());
		Map<Resource,List<CommandWrapper>> class2Query = new HashMap<Resource,List<CommandWrapper>>();
		for(Statement s : JenaUtil.listAllProperties(null, predicate).toList()) {
			if(s.getObject().isResource()) {
				if(filter == null || filter.test(s.getResource())) {
					add(class2Query, s, model, withClass, allowAsk);
				}
			}
		}
		return class2Query;
	}


	private static boolean hasAllNonOptionalArguments(Template template,
			Map<String, RDFNode> bindings) {
		for(Argument arg : template.getArguments(false)) {
			if(!arg.isOptional()) {
				if(!bindings.containsKey(arg.getVarName())) {
					// Don't return this template if any non-optional argument is missing
					return false;
				}
			}
		}
		return true;
	}
}
