/*
    See lda-top/LICENCE (or http://elda.googlecode.com/hg/LICENCE)
    for the licence for this software.
    
    (c) Copyright 2011 Epimorphics Limited
    $Id$

    File:        APIQuery.java
    Created by:  Dave Reynolds
    Created on:  31 Jan 2010
*/

package com.epimorphics.lda.query;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.epimorphics.jsonrdf.Context.Prop;
import com.epimorphics.lda.bindings.Value;
import com.epimorphics.lda.bindings.Bindings;
import com.epimorphics.lda.cache.Cache;
import com.epimorphics.lda.core.APIException;
import com.epimorphics.lda.core.APIResultSet;
import com.epimorphics.lda.core.MultiMap;
import com.epimorphics.lda.core.Param;
import com.epimorphics.lda.core.VarSupply;
import com.epimorphics.lda.core.View;
import com.epimorphics.lda.core.Param.Info;
import com.epimorphics.lda.exceptions.EldaException;
import com.epimorphics.lda.rdfq.*;
import com.epimorphics.lda.shortnames.ShortnameService;
import com.epimorphics.lda.sources.Source;
import com.epimorphics.lda.specs.APISpec;
import com.epimorphics.lda.support.LARQManager;
import com.epimorphics.lda.support.PrefixLogger;
import com.epimorphics.lda.support.QuerySupport;

import com.epimorphics.util.CollectionUtils;
import com.epimorphics.util.Couple;
import com.hp.hpl.jena.graph.*;
import com.hp.hpl.jena.query.*;
import com.hp.hpl.jena.rdf.model.*;
import com.hp.hpl.jena.shared.PrefixMapping;
import com.hp.hpl.jena.sparql.engine.http.QueryExceptionHTTP;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;
import com.hp.hpl.jena.vocabulary.RDF;
import com.hp.hpl.jena.vocabulary.RDFS;

/**
 * Query abstraction that supports assembling multiple filter/order/view
 * specifications into a set of working sparql queries.
 * 
 * @author <a href="mailto:der@epimorphics.com">Dave Reynolds</a>
 * @version $Revision: $
 */
public class APIQuery implements Cloneable, VarSupply, ExpansionPoints {
    
	public static final String NEAR_LAT = "near-lat";
	
	public static final String NEAR_LONG = "near-long";
    
	public static final String SELECT_VARNAME = "item";
    
    public static final Variable SELECT_VAR = RDFQ.var( "?" + SELECT_VARNAME );
    
    public static final String PREFIX_VAR = "?___";
    
    // Partial elements of the SELECT pattern 
    //  It would more elegant to use ARQ syntax tree Element/Expr
    //  but not sure how well that plays with JDO persistance for GAE
    //  so stick to string bashing for ease of debug and persistence
    
    protected StringBuffer whereExpressions = new StringBuffer();
    
    private StringBuffer orderExpressions = new StringBuffer();
    
    /**
        List of pseudo-triples which form the basic graph pattern element
        of this query.
    */
    protected List<RDFQ.Triple> basicGraphTriples = new ArrayList<RDFQ.Triple>();
    
    public List<RDFQ.Triple> getBasicGraphTriples() {
    	// FOR TESTING ONLY
    	return basicGraphTriples;
    }
    
    /**
        List of little infix expressions (operands must be RDFQ.Any's) which
        are SPARQL filters for this query. 
    */
    protected List<RenderExpression> filterExpressions = new ArrayList<RenderExpression>();
    
    public List<RenderExpression> getFilterExpressions() {
    	// FOR ETSTING ONLY
    	return filterExpressions;
    }
    
    public void addFilterExpression( RenderExpression e ) {
    	filterExpressions.add( e );
    }
    
    protected String viewArgument = null;
    
    protected final ShortnameService sns;
    protected String defaultLanguage = null;
    
    protected int varcount = 0;
    
    protected final int defaultPageSize;
    protected int pageSize = QueryParameter.DEFAULT_PAGE_SIZE;
    protected final int maxPageSize;
    
    protected int pageNumber = 0;
        
    protected Map<Variable, Info> varInfo = new HashMap<Variable, Info>();
    
    protected Resource subjectResource = null;
    
    protected String itemTemplate;
    protected String fixedSelect = null;
    
    protected Set<String> metadataOptions = new HashSet<String>();
    
    // TODO replace this by full property chain descriptions
    protected Set<Property> expansionPoints = new HashSet<Property>();

    /**
        Set to true to switch on LARQ indexing for this query, ie when an
        _search wossname is being used.
    */
    protected boolean needsLARQindex = false;
    
    static Logger log = LoggerFactory.getLogger( APIQuery.class );

    public APIQuery( ShortnameService sns ) {
        this( fakeQB(sns) );
    }
    
    private static final QueryBasis fakeQB( final ShortnameService sns ) {
    	return new QueryBasis() {
    		@Override public final ShortnameService sns() { return sns; }
    		@Override public final String getDefaultLanguage() { return null; }
    		@Override public String getItemTemplate() { return null; }
    		@Override public final int getMaxPageSize() { return QueryParameter.MAX_PAGE_SIZE; }
    		@Override public final int getDefaultPageSize() { return QueryParameter.DEFAULT_PAGE_SIZE; }
    	};
    }
    

	/**
        The parameters that form the basis of an API Query.
     
     	@author chris
    */
    public interface QueryBasis {
    	ShortnameService sns();
    	String getDefaultLanguage();
    	int getMaxPageSize();
    	int getDefaultPageSize();
		String getItemTemplate();
    }

    public APIQuery( QueryBasis qb ) {
        this.sns = qb.sns();
        this.defaultLanguage = qb.getDefaultLanguage();
        this.pageSize = qb.getDefaultPageSize();
        this.defaultPageSize = qb.getDefaultPageSize();
        this.maxPageSize = qb.getMaxPageSize();
        this.itemTemplate = qb.getItemTemplate();
    }

    @Override public APIQuery clone() {
        try {
            APIQuery clone = (APIQuery) super.clone();
            clone.basicGraphTriples = new ArrayList<RDFQ.Triple>( basicGraphTriples );
            clone.filterExpressions = new ArrayList<RenderExpression>( filterExpressions );
            clone.orderExpressions = new StringBuffer( orderExpressions );
            clone.whereExpressions = new StringBuffer( whereExpressions );
            clone.varInfo = new HashMap<Variable, Info>( varInfo );
            clone.expansionPoints = new HashSet<Property>( expansionPoints );
            clone.deferredFilters = new ArrayList<PendingParameterValue>( deferredFilters );
            clone.metadataOptions = new HashSet<String>( metadataOptions );
            clone.varsForPropertyChains = new HashMap<String, Variable>( varsForPropertyChains );
            return clone;
        } catch (CloneNotSupportedException e) {
            throw new APIException("Can't happen :)", e);
        }
    }
    
    /**
     * Set the page size to use when paging through results.
     * If this is not called then a default size will be used.
     */
    public void setPageSize( int pageSize ) {
        this.pageSize = (pageSize > maxPageSize ? defaultPageSize : pageSize); // Math.min(pageSize, maxPageSize );
    }
    
    public int getPageSize() {
        return pageSize;
    }

    /**
     * Set which page should be returned.
     */
    public void setPageNumber(int page) {
        this.pageNumber = page;
    }

    public int getPageNumber() {
        return pageNumber;
    }
    
    public void setTypeConstraint( Resource typeConstraint ) {
        addTriplePattern( SELECT_VAR, RDF.type, RDFQ.uri( typeConstraint.getURI() ) );
    }
    
    /**
     * Sets the query to just describe a single resource, rather than
     * search for a list
     * @param subj the target resource as either a prefix_name string or as a full URI
     */
    public void setSubject(String subj) {
        subjectResource = sns.asResource(subj);
    }
    
    /**
     * Return true if this query is a fixed subject instead of a select
     */
    public boolean isFixedSubject() {
        return subjectResource != null;
    }
    
    /**
     * Return the fixed subject for a subject query, null for a select query
     */
    public String getSubject() {
        return subjectResource.getURI();
    }
    
    private Map<String, String> languagesFor = new HashMap<String, String>();
        
    /**
        Set the default language, discarding any existing default language.
    */
    public void setDefaultLanguage( String defaults ) {
    	defaultLanguage = defaults;
    	}
    
    /**
        Answer the (current) default language string.
    */
    public String getDefaultLanguage() {
    	return defaultLanguage;
    }
    
    public void clearLanguages() {
    	languagesFor.clear();
    }
    
    public void setLanguagesFor( String fullParamName, String languages ) {
    	languagesFor.put( fullParamName, languages );    
    }
 
	public void addMetadataOptions( Set<String> options ) {
		metadataOptions.addAll( options );
	}
	
	public void addMetadataOptions( String [] options ) {
		for (String option: options) metadataOptions.add( option.toLowerCase() );
	}
    
    public List<PendingParameterValue> deferredFilters = new ArrayList<PendingParameterValue>();
    
    public void deferrableAddFilter( Param param, String val ) {
    	deferredFilters.add( new PendingParameterValue( param, val ) );
    }
    
    public void setViewByTemplateClause( String clause ) {
    	viewArgument = clause;
    }
            
    /**
     * Record a required expansion point, i.e. a view path prop.*
     * TODO Extend to full paths instead of just single step 
     */
    @Override public void addExpansion(String uri) {
        expansionPoints.add( ResourceFactory.createProperty(uri) );
    }
    
    protected final static Resource PF_TEXT_MATCH = ResourceFactory.createProperty( "http://jena.hpl.hp.com/ARQ/property#textMatch" );
    
    protected void addSearchTriple( String val ) {
    	needsLARQindex = true;
        log.debug( "enabled LARQ indexing to search for " + val );
        addTriplePattern( SELECT_VAR, PF_TEXT_MATCH, asStringLiteral(val) );
    }
    
    public APIQuery addSubjectHasProperty( Resource P, Any O ) {
        addTriplePattern( SELECT_VAR, P, O );
        return this;
    }

	public void addNumericRangeFilter( Variable v, double x, double dx ) {
		addInfixSparqlFilter( RDFQ.literal( x - dx ), "<", v );
		addInfixSparqlFilter( v, "<", RDFQ.literal( x + dx) );
	}
    
    private void addInfixSparqlFilter( Any v, String op, Any literal ) {
    	addFilterExpression( RDFQ.infix( v, op, literal ) );
	}

	private void addTriplePattern( Variable varname, Resource P, Any O ) {
        basicGraphTriples.add( RDFQ.triple( varname, RDFQ.uri( P.getURI() ), O ) );
    }
    
    /**
         Update this query-generator with a bunch of basic graph triples to
         use. Note: the argument is a list; the order is preserved apart from
         the special re-ordering rules.
    */
    public void addTriplePatterns( List<RDFQ.Triple> triples ) {
    	basicGraphTriples.addAll( triples );
    }   

	private void addTriplePattern( Variable var, Param.Info prop, Variable val ) {
   		// Record property which points to this variable for us in decoding binding values
   		varInfo.put( val, prop );
        basicGraphTriples.add( RDFQ.triple( var, prop.asURI, val ) );
    }

    protected void addPropertyHasValue( Param param ) {
    	addPropertyHasValue( param, newVar() );
    }
    
    protected void addPropertyHasValue( Param param, Variable O ) {
    	addPropertyHasValue( param, O.name() );    	
    }
    
    protected Map<String, Variable> varsForPropertyChains = new HashMap<String, Variable>();
    
    protected void addPropertyHasValue( Param param, String val ) {
    	String languages = languagesFor.get( param.toString() );
		if (languages == null) languages = defaultLanguage;
		Param.Info [] infos = param.fullParts();
		String dot = "";
	//
		StringBuilder chainName = new StringBuilder();
	//
		Variable var = SELECT_VAR;
	    int i = 0;
	    while (i < infos.length-1) {
	    	Param.Info inf = infos[i];
	    	chainName.append( dot ).append( inf.shortName );
	    	Variable v = varsForPropertyChains.get( chainName.toString() );
	    	if (v == null) {
	    		v = RDFQ.var( PREFIX_VAR + chainName.toString().replaceAll( "\\.", "_" ) + "_" + varcount++ );
	    		varsForPropertyChains.put( chainName.toString(), v );
	    		addTriplePattern(var, inf, v );
	    	}
	    	dot = ".";
	    	var = v;
	        i++;
	    }
	    // System.err.println( varsForPropertyChains );
	//
	    Info inf = infos[i];
	    String prop = inf.shortName;
	    if (val.startsWith("?")) varInfo.put( RDFQ.var(val), inf );
	    if (languages == null) {
			// System.err.println( ">> addTriplePattern(" + prop + "," + val + ", " + languages + ")" );
			Any norm = sns.valueAsRDFQ( prop, val, null );
			addTriplePattern( var, inf.asResource, norm ); 
	    } else {
			// System.err.println( ">> addTriplePattern(" + prop + "," + val + ", " + languages + ")" );
			addLanguagedTriplePattern( var, inf, languages, val );
	    }
    }
	
	private void addLanguagedTriplePattern(Variable var, Info inf, String languages, String val) {
		String prop = inf.shortName;
		Resource np = inf.asResource;
		String[] langArray = languages.split( "," );
		Prop p = sns.asContext().getPropertyByName( prop );
	//
		if (langArray.length == 1 || (p != null && p.getType() != null)) {
			addTriplePattern( var, np, sns.valueAsRDFQ( prop, val, langArray[0] ) ); 
		} else if (val.startsWith( "?" )) {
			Variable v = RDFQ.var( val );
			addTriplePattern( var, np, v );
			filterExpressions.add( someOf( v, langArray ) );
		} else {
			Variable v = newVar();
			addTriplePattern( var, np, v );
			Apply stringOf = RDFQ.apply( "str", v );
			Infix equals = RDFQ.infix( stringOf, "=", asStringLiteral(val) );
			Infix filter = RDFQ.infix( equals, "&&", someOf( v, langArray ) );
			filterExpressions.add( filter );
		}
	}
	
	public Any asStringLiteral( String val ) {
		return RDFQ.literal( val );
	}
    
    private RenderExpression someOf( Variable v, String[] langArray ) 
    	{
    	Apply langOf = RDFQ.apply( "lang", v );
		RenderExpression result = RDFQ.infix( langOf, "=", squelchNone( langArray[0] ) );
    	for (int i = 1; i < langArray.length; i += 1)
    		result = RDFQ.infix( result, "||", RDFQ.infix( langOf, "=", squelchNone( langArray[i] ) ) );
    	return result;
    	}

	private Any squelchNone( String lang ) {
		return RDFQ.literal( lang.equals( "none" ) ? "" : lang );
	}
    
    protected void addPropertyHasntValue( Param param ) {
    	Variable var = newVar();
    	filterExpressions.add( RDFQ.apply( "!", RDFQ.apply( "bound", var ) ) );
		Param.Info [] infos = param.fullParts();
	//
		Variable s = SELECT_VAR;
		int remaining = infos.length;
	//
		for (Param.Info inf: infos) {
			remaining -= 1;
			Variable o = remaining == 0 ? var : newVar();
			onePropertyStep( s, inf, o );
			s = o;
		}
    }  

	private void onePropertyStep( Variable subject, Info prop, Variable var ) {
		Resource np = prop.asResource;
		varInfo.put( var, prop );
		basicGraphTriples.add( RDFQ.triple( subject, RDFQ.uri( np.getURI() ), var, true ) ); 
	}

    /**
        Discard any existing order expressions (a string that
        may appear after SPARQL's ORDER BY). Add <code>orderBy</code>
        as the new order expressions.
    */
    public void setOrderBy( String orderBy ) {
    	orderExpressions.setLength(0);
    	orderExpressions.append( orderBy );
    }
    
    public void setFixedSelect( String fixedSelect ) {
    	this.fixedSelect = fixedSelect;
    }
    
    /**
        Discard any existing order expressions. Decode
        <code>orderSpec</code> to produce a new order expression.
        orderSpec is a comma-separated list of sort fields,
        each optionally proceeded by - for DESC. If the field
        is a variable, it is used as-is, otherwise it is assumed
        to be a short property name and an additional triple
        (?item Property Var) added to the query with the Var
        being the sort field.
    */
    public void setSortBy( String orderSpecs ) {
    	orderExpressions.setLength(0);
    	for (String spec: orderSpecs.split(",")) 
    		if (spec.length() > 0){
		        boolean descending = spec.startsWith("-"); 
		        if (descending) spec = spec.substring(1);
		        boolean varOrder = spec.startsWith("?");
		        String var = varOrder ? spec : newVar().name(); // TODO
		        Variable v = RDFQ.var( var );
		        if (descending) {
		        	orderExpressions.append(" DESC(" + var + ") ");
		        } else {
		            orderExpressions.append(" " + var + " ");
		        }
		        if (!varOrder) {
		        	Param p = Param.make(sns, spec);
					addPropertyHasValue( p, v );
		        }
    		}
    }
    
    @Override public Variable newVar() {
        return RDFQ.var( PREFIX_VAR + varcount++ );
    }
    
    /**
        Answer the number of variables allocated so far (used
        for testing).
    */
    public int countVarsAllocated() {
    	return varcount;
    }

    protected void addNameProp(Param param, String literal) {
        Variable newvar = newVar();
        addPropertyHasValue( param, newvar );
        addTriplePattern( newvar, RDFS.label, asStringLiteral( literal ) );
    }
    
    private static final Pattern varPattern = Pattern.compile("\\?[a-zA-Z]\\w*");
    
	public void addWhere( String whereClause ) {
		log.debug( "TODO: check the legality of the where clause: " + whereClause );
        if (whereExpressions.length() > 0) whereExpressions.append(" ");
        whereExpressions.append(whereClause);
    }

    public String assembleSelectQuery( Bindings cc, PrefixMapping prefixes ) {  	
    	PrefixLogger pl = new PrefixLogger( prefixes );   
    	return assembleRawSelectQuery( pl, cc );
    }

    public String assembleSelectQuery( PrefixMapping prefixes ) {     	
    	PrefixLogger pl = new PrefixLogger( prefixes );
    	Bindings cc = Bindings.createContext( new Bindings(), new MultiMap<String, String>() );
    	return assembleRawSelectQuery( pl, cc );
    }
    
    public String assembleRawSelectQuery( PrefixLogger pl, Bindings cc ) { 
    	if (fixedSelect == null) {
	        StringBuilder q = new StringBuilder();
	        q.append("SELECT ");
	        if (orderExpressions.length() > 0) q.append("DISTINCT "); // Hack to work around lack of _select but seems a common pattern
	        q.append( SELECT_VAR.name() );
	        q.append("\nWHERE {\n");
	        String bgp = constructBGP( pl );
	        if (whereExpressions.length() > 0) {
	        	q.append( whereExpressions ); 
	        	pl.findPrefixesIn( whereExpressions.toString() );
	        } else {
		        if (bgp.isEmpty()) bgp = SELECT_VAR.name() + " ?__p ?__v ."; 
	        }
	        q.append( bgp );
	        appendFilterExpressions( pl, q );
	        q.append( "} " );
	        if (orderExpressions.length() > 0) {
	            q.append(" ORDER BY ");
	            q.append( orderExpressions );
	        	pl.findPrefixesIn( orderExpressions.toString() );
	        }
	        appendOffsetAndLimit( q );
//	         System.err.println( ">> QUERY IS: \n" + q.toString() );
	        String bound = bindDefinedvariables( pl, q.toString(), cc );
	        StringBuilder x = new StringBuilder();
	        pl.writePrefixes( x );
			x.append( bound );
	        return x.toString();
    	} else {
    		// TODO add code for LIMIT/OFFSET when tests exist.
    		pl.findPrefixesIn( fixedSelect );
    		String bound = bindDefinedvariables( pl, fixedSelect, cc );
    		StringBuilder sb = new StringBuilder();
    		pl.writePrefixes( sb );
			sb.append( bound );
    		appendOffsetAndLimit( sb );
    		return sb.toString();
    	}
    }

	private void appendOffsetAndLimit(StringBuilder q) {
		q.append(" OFFSET " + (pageNumber * pageSize));
		q.append(" LIMIT " + pageSize);
	}

	public void appendFilterExpressions(PrefixLogger pl, StringBuilder q ) {
		for (RenderExpression i: filterExpressions) {
			q.append( " FILTER (" );
			i.render( pl, q );
			q.append( ")\n" );
		}				
	}

	public String constructBGP( PrefixLogger pl ) {
		StringBuilder sb = new StringBuilder();
		for (RDFQ.Triple t: QuerySupport.reorder( basicGraphTriples ))
			sb
				.append( t.asSparqlTriple( pl ) )
				.append( " .\n" )
				;
		return sb.toString();
	}

	/**
	    Add SPARQL prefix declarations for all the prefixes in
	    <code>pm</code> to the StringBuilder <code>q</code>.
	*/
	private void appendPrefixes( StringBuilder q, PrefixMapping pm ) {
		for (String prefix: pm.getNsPrefixMap().keySet()) {
			q
				.append( "PREFIX " )
				.append( prefix )
				.append( ": <" )
				.append( pm.getNsPrefixURI(prefix).trim() ) // !! TODO
				.append( ">\n" );
		}
	}
    
	/**
	    Take the SPARQL query string <code>query</code> and replace any ?SPOO
	    where SPOO is a variable bound in <code>cc</code> with the SPARQL
	    representation of that variable's value. Note that this will include
	    <i>any</i> occurrences of ?SPOO, including those inside SPARQL quotes.
	    Fixing this probably should happen earlier, but note that bits of query
	    are mashed together from strings in the config file, ie, without going
	    through RDFQ.	    
	*/
    protected String bindDefinedvariables( PrefixLogger pl, String query, Bindings cc ) {
//    	System.err.println( ">> query is: " + query );
//    	System.err.println( ">> VarValues is: " + cc );
    	StringBuilder result = new StringBuilder( query.length() );
    	Matcher m = varPattern.matcher( query );
    	int start = 0;
    	while (m.find( start )) {
    		result.append( query.substring( start, m.start() ) );
    		String name = m.group().substring(1);
    		Value v = cc.get( name );
//    		System.err.println( ">> value of " + name + " is " + v );
    		if (v == null) {
    			result.append( m.group() );
    		} else {
	    		Info prop = varInfo.get( RDFQ.var( "?" + name ) );
	            String val = cc.getValueString( name );
//	            if (name.equals( "value" )) 
//	            	{
//	            	System.err.println( ">> value = " + v );
//	            	System.err.println( ">> prop = " + prop );
//	            	System.err.println( ">> val = " + val );
//	            	}
	        	String normalizedValue = 
	        		(prop == null) 
	        		    ? valueAsSparql( v )
	        		    : sns.valueAsRDFQ(prop.shortName, val, defaultLanguage).asSparqlTerm(pl); 
	    		result.append( normalizedValue );
    		}
    		start = m.end();
    	}
    	result.append( query.substring( start ) );
    	return result.toString();
    }

    private String valueAsSparql( Value v ) {
    	String type = v.type();
    	if (type.equals( "" )) return "'" + protect(v.valueString()) + "'";
    	if (type.equals( RDFS.Resource.getURI() )) return "<" + v.valueString() + ">";
    	throw new RuntimeException( "valueAsSparql: cannot handle type: " + type );
    }

	private String protect(String valueString) {
		return valueString
			.replaceAll( "\\\\", "\\\\" )
			.replaceAll( "'", "\\'" )
			;
	}

	/**
     * Return the select query that would be run or a plain string for the resource
     */
    public String getQueryString(APISpec spec, Bindings call) {
        return isFixedSubject()
            ? "<" + subjectResource.getURI() + ">"
            : assembleSelectQuery( call, spec.getPrefixMap() )
            ;
    }
    
    /**
        Run the defined query against the datasource
    */
    public APIResultSet runQuery( APISpec spec, Cache cache, Bindings call, View view ) {
        Source source = spec.getDataSource();
        try {
        	return runQueryWithSource( spec, cache, call, view, source );
        } catch (QueryExceptionHTTP e) {
            EldaException.ARQ_Exception( source, e );
            return /* NEVER */ null;
        }
    }

	private APIResultSet runQueryWithSource( APISpec spec, Cache cache, Bindings call, View view, Source source ) {
		long origin = System.currentTimeMillis();
		Couple<String, List<Resource>> queryAndResults = selectResources( cache, spec, call, source );
		long afterSelect = System.currentTimeMillis();
		
		String outerSelect = queryAndResults.a;
		List<Resource> results = queryAndResults.b;
		
		// System.err.println( ">> looking in cache " + cache.summary() );
		APIResultSet already = cache.getCachedResultSet( results, view.toString() );
		if (already != null && expansionPoints.isEmpty() ) 
		    {
		    log.debug( "re-using cached results for " + results );
		    return already.clone();
		    }
		
		APIResultSet rs = fetchDescriptionOfAllResources(outerSelect, spec, view, results);
		
		long afterView = System.currentTimeMillis();
		
		log.debug( "TIMING: select time: " + (afterSelect - origin)/1000.0 + "s" );
		log.debug( "TIMING: view time:   " + (afterView - afterSelect)/1000.0 + "s" );
		rs.setSelectQuery( outerSelect );
		
		// Expand chained views, if present
		if ( ! expansionPoints.isEmpty()) {
		    for (Property exp : expansionPoints) {
		        expandResourcesOf(exp, rs, view, spec );
		    }
		} else {
		    // Can't cache results which use expansion points
		    cache.cacheDescription( results, view.toString(), rs.clone() );
		}
		return rs;
	}

	private APIResultSet fetchDescriptionOfAllResources(String select, APISpec spec, View view, List<Resource> results) {
		int count = results.size();
		Model descriptions = ModelFactory.createDefaultModel();
		Graph gd = descriptions.getGraph();
		String detailsQuery = fetchDescriptionsFor( select, results, view, descriptions, spec );
		return new APIResultSet(gd, results, count < pageSize, detailsQuery );
	}

    /** Find all current values for the given property on the results and fetch a description of them */
    private void expandResourcesOf(Property exp, APIResultSet rs, View view, APISpec spec ) {
    	Model rsm = rs.getModel();
        List<Resource> toExpand = new ArrayList<Resource>();
        for (Resource root : rs.getResultList()) {
            NodeIterator ni = rsm.listObjectsOfProperty(root, exp);
            while (ni.hasNext()) {
                RDFNode n = ni.next();
                if (n.isAnon()) {
                    if (n.canAs(RDFList.class)) {
                        RDFList list = n.as(RDFList.class);
                        ExtendedIterator<RDFNode> li = list.iterator();
                        while (li.hasNext()) {
                            RDFNode l = li.next();
                            if (l.isURIResource()) toExpand.add( (Resource)l );
                        }
                    }
                } else if (n.isURIResource()) {
                    toExpand.add( (Resource)n );
                }
            }
        }
        System.err.println( "property.* not implemented at the moment." );
        if (true) throw new UnsupportedOperationException( "property.* not implemented at the moment." ); // TODO
        fetchDescriptionsFor( "\nSELECT ?item\n WHERE {}", toExpand, view, rsm, spec);
    }
    
    // let's respect property chains ...
    private String fetchDescriptionsFor( String select, List<Resource> roots, View view, Model m, APISpec spec ) {
        if (roots.isEmpty() || roots.get(0) == null) return "# no results, no query.";
        List<Source> sources = spec.getDescribeSources();
        m.setNsPrefixes( spec.getPrefixMap() );
        return viewArgument == null
        	? view.fetchDescriptions( new View.State( select, roots, m, sources, this ) )
        	: viewByTemplate( roots, m, spec, sources )
        	;
    }

	private String viewByTemplate(List<Resource> roots, Model m, APISpec spec, List<Source> sources) {
		StringBuilder clauses = new StringBuilder();
		for (Resource root: roots)
			clauses
				.append( "  " )
				.append( viewArgument.replaceAll( "\\?item", "<" + root.getURI() + ">" ) )
				.append( "\n" )
				;
		StringBuilder query = new StringBuilder( clauses.length() * 2 + 17 );
		appendPrefixes( query, spec.getPrefixMap() );
		query
			.append( "CONSTRUCT {\n" )
			.append( clauses )
			.append( "} where {" )
			.append( clauses )
			.append( "}\n" )
			;
		String qq = query.toString();
		Query cq = QueryFactory.create( qq );
		for (Source x: sources) m.add( x.executeConstruct( cq ) );		
		return qq;
	}

	/**
	    Answer the select query (if any; otherwise, "") and list of resources obtained by
	    running that query.
	*/
    private Couple<String, List<Resource>> selectResources( Cache cache, APISpec spec, Bindings call, Source source ) {
    	log.debug( "fetchRequiredResources()" );
        final List<Resource> results = new ArrayList<Resource>();
        if (itemTemplate != null) setSubject( call.expandVariables( itemTemplate ) );
        if ( isFixedSubject() )
            return new Couple<String, List<Resource>>( "", CollectionUtils.list( subjectResource ) );
        else
        	return runGeneralQuery( cache, spec, call, source, results );
    }

	private Couple<String, List<Resource>> runGeneralQuery( Cache cache, APISpec spec, Bindings cc, Source source, final List<Resource> results) {
		String select = assembleSelectQuery( cc, spec.getPrefixMap() );
		List<Resource> already = cache.getCachedResources( select );
		if (already != null)
		    {
		    log.debug( "re-using cached results for query " + select );
		    return new Couple<String, List<Resource>>(select, already);
		    }
		Query q = createQuery( select );
		log.debug( "Running query: " + select.replaceAll( "\n", " " ) );
		source.executeSelect( q, new ResultResourcesReader( results, needsLARQindex ) );
		cache.cacheSelection( select, results );
		return new Couple<String, List<Resource>>( select, results );
	}

	private Query createQuery( String selectQuery ) {
		try 
			{ return QueryFactory.create(selectQuery); } 
		catch (Exception e) {
		    throw new APIException("Internal error building query: " + selectQuery, e);
		}
	}
	
	private static final class ResultResourcesReader implements Source.ResultSetConsumer {
		
		private final List<Resource> results;
		private final boolean needsLARQindex;

		private ResultResourcesReader( List<Resource> results, boolean needsLARQindex ) {
			this.results = results;
			this.needsLARQindex = needsLARQindex;
		}

		@Override public void setup( QueryExecution qe ) {
		    if (needsLARQindex) LARQManager.setLARQIndex( qe );				
		}

		@Override public void consume( ResultSet rs ) {
			try {
				while (rs.hasNext()) {
					Resource item = rs.next().getResource( SELECT_VAR.name() );
					if (item == null) {
						EldaException.BadSpecification
						( "<br>Oops. No binding for " + SELECT_VAR.name() + " in successful SELECT.\n"
								+ "<br>Perhaps ?item was mis-spelled in an explicit api:where clause.\n"
								+ "<br>It's not your fault; contact the API provider."                			
						);                		
					}
					results.add( withoutModel( item ) );
				}
			} catch (APIException e) {
				throw e;
			} catch (Throwable t) {
				throw new APIException("Query execution problem on query: " + t, t);
			}				
		}

		// because resource.inModel(null) explodes.
		private Resource withoutModel(Resource item) {
			return ResourceFactory.createResource( item.getURI() );
		}
	}

	public boolean wantsMetadata( String name ) {
		return metadataOptions.contains( name ) || metadataOptions.contains( "all" );
	}

}

