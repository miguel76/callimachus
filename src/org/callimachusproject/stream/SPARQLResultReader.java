/*
 * Portions Copyright (c) 2009-10 Zepheira LLC and James Leigh, Some
   Rights Reserved
 * Portions Copyright (c) 2010-11 Talis Inc, Some Rights Reserved 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.callimachusproject.stream;

import static org.callimachusproject.stream.SPARQLWriter.toSPARQL;
import static org.openrdf.query.QueryLanguage.SPARQL;

import java.io.IOException;

import org.callimachusproject.rdfa.RDFEventReader;
import org.callimachusproject.rdfa.RDFParseException;
import org.callimachusproject.rdfa.events.Document;
import org.callimachusproject.rdfa.events.RDFEvent;
import org.callimachusproject.rdfa.events.TriplePattern;
import org.callimachusproject.rdfa.model.VarOrTerm;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.query.GraphQuery;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.repository.RepositoryConnection;
import org.openrdf.repository.RepositoryException;

/**
 * Evaluates queries against the RDF store returning a single result document.
 * 
 * @author James Leigh
 * @author Steve Battle
 * 
 */
public class SPARQLResultReader extends RDFEventReader {
	private TriplePatternStore patterns;
	private RDFEventReader graphResult;
	private boolean started, ended = false;
	
	/* Assume that the query is a CONSTRUCT query */
	
	public SPARQLResultReader(String sparql, TriplePatternStore store, RepositoryConnection con)
			throws RepositoryException, MalformedQueryException,
			QueryEvaluationException, RDFParseException, IOException {
		this.patterns = store;
		
		if (sparql != null) {
			GraphQuery q = con.prepareGraphQuery(SPARQL, sparql);
			graphResult = new GraphResultReader(q.evaluate());
		}
	}
	
	/* Assume that the query is a CONSTRUCT query */

	public SPARQLResultReader(TriplePatternStore store,
			RepositoryConnection con, String uri) throws RepositoryException,
			MalformedQueryException, QueryEvaluationException,
			RDFParseException, IOException {
		this.patterns = store;

		RDFEventReader query = patterns.openQueryReader();
		if (query==null) return;
		
		//System.out.println(toSPARQL(patterns.openQueryReader()));
		
		GraphQuery q = con.prepareGraphQuery(SPARQL, toSPARQL(query));

		if (uri != null) {
			ValueFactory vf = con.getValueFactory();
			URI self = vf.createURI(uri);
			q.setBinding("this", self);
			
			/* The following is required for evaluating document fragments from forms
			 * in that case the first 'subject' is not '?this' */
			TriplePattern tp = patterns.getFirstTriplePattern();
			// tp may be null if the where clause is empty
			if (tp==null);
			// the 'subject' of tp may not be ?this
			else if (tp.isInverse()) {
				VarOrTerm obj = tp.getObject();
				if (obj.isVar()) {
					q.setBinding(obj.stringValue(), self);
				}
			} 
			else {
				VarOrTerm subj = tp.getSubject();
				if (subj.isVar()) {
					q.setBinding(subj.stringValue(), self);
				}
			}
		}
		graphResult = new ReducedTripleReader(new GraphResultReader(q.evaluate())) ;
	}
	
	public String toString() {
		return patterns.toString();
	}

	@Override
	public void close() throws RDFParseException {
		if (graphResult != null) graphResult.close();
	}

	@Override
	protected RDFEvent take() throws RDFParseException {
		try {
			if (!ended) {
				if (!started) {
					started = true;
					return new Document(true);	
				}
				if (graphResult!=null && graphResult.hasNext()) {
					RDFEvent next = graphResult.next();
					if (next.isStartDocument()) return take();
					if (next.isEndDocument()) {
						graphResult.close();
						graphResult = null;
						return take();
					}
					return next;	
				}
				ended = true;
				return new Document(false);
			}
			return null;
		} 
		catch (Exception e) {
			throw new RDFParseException(e);
		}
	}

}
