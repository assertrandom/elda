/*
    See lda-top/LICENCE (or http://elda.googlecode.com/hg/LICENCE)
    for the licence for this software.
    
    (c) Copyright 2012 Epimorphics Limited
    $Id$
*/

package com.epimorphics.lda.vocabularies.test;

import static org.junit.Assert.*;

import org.junit.Test;

import com.epimorphics.jsonrdf.RDFUtil;
import com.epimorphics.lda.vocabularies.EXTRAS;

/**
    Ensure that the EXTRAS elements for missing list elements/tails,
    which have their home in RDFUtil.Vocab, are exactly as they should 
    be when seen from EXTRAS.
*/
public class TestCrosslinkedVocabularies {

	@Test public void RDF_Vocab_shares_NS_with_EXTRAS() {
		assertEquals( EXTRAS.EXTRA, RDFUtil.Vocab.NS );
	}
	
	@Test public void ensure_imported_constants_match() {
		assertEquals( RDFUtil.Vocab.missingListElement, EXTRAS.missingListElement );
		assertEquals( RDFUtil.Vocab.missingListTail, EXTRAS.missingListTail );
	}
	
	@Test public void ensure_spellings_match_names() {
		assertEquals( "missingListTail", RDFUtil.Vocab.missingListTail.getLocalName() );
		assertEquals( "missingListElement", RDFUtil.Vocab.missingListElement.getLocalName() );
	}
}