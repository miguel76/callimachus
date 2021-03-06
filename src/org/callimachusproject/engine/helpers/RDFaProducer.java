/*
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

package org.callimachusproject.engine.helpers;

import java.lang.reflect.UndeclaredThrowableException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.regex.Pattern;

import javax.xml.namespace.NamespaceContext;
import javax.xml.namespace.QName;
import javax.xml.stream.Location;
import javax.xml.stream.XMLEventFactory;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Namespace;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.callimachusproject.engine.RDFParseException;
import org.callimachusproject.engine.expressions.ExpressionUtil;
import org.callimachusproject.engine.impl.FallbackLocation;
import org.callimachusproject.engine.model.AbsoluteTermFactory;
import org.callimachusproject.engine.model.TermOrigin;
import org.openrdf.model.BNode;
import org.openrdf.model.Literal;
import org.openrdf.model.URI;
import org.openrdf.model.Value;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.openrdf.query.Binding;
import org.openrdf.query.BindingSet;
import org.openrdf.query.QueryEvaluationException;
import org.openrdf.query.TupleQueryResult;
import org.openrdf.query.impl.TupleQueryResultImpl;

/**
 * Produce XHTML+RDFa events from a streamed template and SPARQL result set 
 * 
 * @author Steve Battle
 */

public class RDFaProducer extends XMLEventReaderBase {
	private static final String XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace";
	private static final String BASE_TAG = "http://www.w3.org/1999/xhtmlbase";
	private static final List<String> EMPTY_LIST = Collections.emptyList();
	private static final Iterable<BindingSet> EMPTY_SET = Collections.emptySet();
	private static final Map<String,TermOrigin> EMPTY_MAP = Collections.emptyMap();
	private static final TupleQueryResult EMPTY_RESULT = new TupleQueryResultImpl(EMPTY_LIST, EMPTY_SET);

	final static String[] RDFA_OBJECT_ATTRIBUTES = { "about", "resource", "typeof", "content" };
	final static List<String> RDFA_OBJECTS = Arrays.asList(RDFA_OBJECT_ATTRIBUTES);

	final String[] RDFA_VAR_ATTRIBUTES = { "about", "resource", "href", "src", "typeof", "content" };
	List<String> RDFaVarAttributes = Arrays.asList(RDFA_VAR_ATTRIBUTES);

	// reads the input template
	XMLEventList input;
	XMLEventIterator reader;
	Map<String,TermOrigin> origins;
	TupleQueryResult resultSet;
	BindingSet result;
	Set<Binding> consumed;
	// variables that don't originate directly from the document (ignored here)
	Set<String> extraneous = new HashSet<String>();
	Set<String> branches = new HashSet<String>();
	Stack<Context> stack = new Stack<Context>();
	XMLEventFactory eventFactory = XMLEventFactory.newInstance();
	ValueFactory valueFactory = new ValueFactoryImpl();
	AbsoluteTermFactory termFactory = AbsoluteTermFactory.newInstance();
	Context context = new Context();
	String skipElement = null;
	XMLEvent previous;
	
	class Context {
		int position=1, mark;
		Map<String,Value> assignments = new HashMap<String,Value>();
		String path;
		Value content;
		boolean isBranch=false, isHanging=false;
		StartElement start;
		XMLEvent previousWhitespace=null;
		BindingSet resultOnEntry;

		protected Context() {}
		protected Context(Context context, StartElement start) {
			this.start = start;
			assignments.putAll(context.assignments);
			// all sub-contexts of a hanging context are hanging
			/*isHanging = context.isHanging;*/
			resultOnEntry = result;
		}
	}

	public RDFaProducer(XMLEventReader reader) throws QueryEvaluationException,
			XMLStreamException {
		this(reader, EMPTY_RESULT, EMPTY_MAP);
	}

	public RDFaProducer(XMLEventReader reader, TupleQueryResult resultSet,
			Map<String, TermOrigin> origins)
			throws QueryEvaluationException, XMLStreamException {
		this(new XMLEventList(reader), resultSet, origins);
	}

	private RDFaProducer(XMLEventList list, TupleQueryResult resultSet,
			Map<String, TermOrigin> origins)
			throws QueryEvaluationException {
		super();
		this.input = list;
		this.reader = list.iterator();
		this.origins = origins;
		this.resultSet = resultSet;
		result = nextResult();

		for (String name : resultSet.getBindingNames()) {
			TermOrigin origin = origins.get(name);
			if (origin != null)
				branches.add(origin.getPath());
			else
				extraneous.add(name);
		}
	}

	@Override
	public void close() throws XMLStreamException {
		// do nothing
	}

	@Override
	protected boolean more() throws XMLStreamException {
		try {
			while (reader.hasNext()) {
				if (process(reader.nextEvent())) return true;
			}
			return false;
		}
		catch (RuntimeException e) {
			throw e;
		}
		catch (Error e) {
			throw e;
		}
		catch (Exception e) {
			throw new XMLStreamException(e);
		}
	}
	
	protected BindingSet nextResult() throws QueryEvaluationException {
		// clear the record of consumed bindings
		consumed = new HashSet<Binding>();
		if (resultSet.hasNext())
			return resultSet.next();
		resultSet.close();
		return null;
	}
	
	public String path() {
		StringBuffer b = new StringBuffer();
		for (Iterator<Context> i=stack.iterator(); i.hasNext();)
			b.append("/"+i.next().position);
		return b.toString();
	}
	
	private boolean process(XMLEvent event) throws Exception {
		// add previous whitespace if this is not a start event
		if (isWhitespace(previous)) {
			add(previous);
			context.previousWhitespace = previous;
		}
		
		if (event.isStartDocument()) {
			processStartDocument(event);
		}
		else if (event.isStartElement()) {
			return processStartElement(event);
		}
		else if (event.isEndElement()) {
			return processEndElement(event);
		}
		else if (event.isCharacters()) {
			return processCharacters(event);
		}
		else if (event.getEventType()==XMLEvent.COMMENT) {
			return processComment(event);			
		}
		else if (skipElement==null) {
			add(event);
			previous = event;
			return true;
		}
		previous = event;
		return false;
	}

	private boolean processComment(XMLEvent event) {
		previous = event;
		if (skipElement!=null) return false;
		add(event);
		return true;
	}

	private void processStartDocument(XMLEvent event) {
		add(event);
	}

	private boolean processStartElement(XMLEvent event) throws Exception,
			XMLStreamException {
		StartElement start = event.asStartElement();
		stack.push(context);
		context = new Context(context, start);
		context.path = path();
		// record the start element position in the stream
		context.mark = reader.nextIndex()-1;
		
		if (skipElement==null) {
			context.isBranch = branchPoint(start);
			if (context.isBranch) {					
				// optional properties (in the next result) required deeper in the tree
				// collapse multiple consistent solutions into a single element
				// required for property expressions
				
				// consume results that are consistent with the current assignments
				while (consistent() && result!=null) {
					Value c = assign(start);
					if (context.content==null) context.content = c;
					// if there are no more bindings to consume in the current result then step to the next result
					if (!moreBindings()) result = nextResult();
					else break;
				}
				// if there are no solutions then skip this branch
				// All variables must be bound
				if (!context.isHanging && grounded(start)) {
					if (!complete(start)) context.isHanging = true;
					addStartElement(start);
				}
				else skipElement = context.path;
			} else addStartElement(start);
		}
		previous = event;
		return skipElement==null;
	}

	private boolean processEndElement(XMLEvent event) throws Exception {
		if (skipElement!=null) {
			if (context.path.equals(skipElement)) skipElement = null;
			context = stack.pop();
			context.position++;
			previous = event;
			return false;
		}
		add(event);
		previous = event;
		
		// has the current result been entirely consumed?
		if (result!=null && consumed()) result = nextResult(); 

		// don't repeat if we haven't consumed any results
		if (context.isBranch && result!=null && complete() && context.resultOnEntry!=result) {
			int mark = context.mark;
			XMLEvent ws = context.previousWhitespace;
			context = stack.pop();
			// Use preceding whitespace from outer context if inner context had none
			if (ws==null) ws = context.previousWhitespace;
			
			context.position++;
			if (consistent()) {
				reader = input.listIterator(mark);
				context.position--;
				if (ws!=null) previous = ws;
			}
		}
		else {
			context = stack.pop();
			context.position++;
		}
		return true;
	}

	private boolean processCharacters(XMLEvent event) throws RDFParseException {
		previous = event;
		if (skipElement!=null) return false;
		// postpone whitespace, add/repeat in advance of the next event
		if (isWhitespace(event)) return false;
		String text = ExpressionUtil.substitute(event.asCharacters().getData(), event.getLocation(), context.assignments, origins, context.start.getNamespaceContext());
		if (text!=null) add(createCharacters(text, event.getLocation()));
		else add(event);
		return true;
	}

	/* Is the next result consumable in this context  */
	
	private boolean complete() {
		for (Iterator<?> i=result.iterator(); i.hasNext();) {
			Binding b = (Binding) i.next();
			if (context.assignments.containsKey(b.getName())) continue;
			TermOrigin value = origins.get(b.getName());
			if (value == null) continue;
			if (!value.startsWith(context.path)) return false;
		}
		return true;
	}
	
	/* has every result binding been consumed.
	 * Not necessarily all in the current context, 
	 * previous siblings may have consumed bindings */

	private boolean consumed() {
		for (Iterator<?> i=result.iterator(); i.hasNext();) {
			Binding b = (Binding) i.next();
			if (!extraneous.contains(b.getName()) && !consumed.contains(b)) return false;
		}
		return true;
	}
	
	private boolean branchPoint(StartElement start) {
		if (branches.contains(context.path)) return true;
		// RDFa may not identify variable first-use as origin
		for (Iterator<?> i = start.getAttributes(); i.hasNext();) {
			Attribute attr = (Attribute) i.next();
			if (RDFaVarAttributes.contains(attr.getName().getLocalPart()) 
			&& attr.getName().getNamespaceURI().isEmpty()
			&& attr.getValue().startsWith("?")) 
				return true;
		}
		return false;
	}

	private boolean moreBindings() {
		if (result==null) return false;
		for (Iterator<Binding> i=result.iterator(); i.hasNext();) {
			Binding b = i.next();
			if (!context.assignments.keySet().contains(b.getName()) 
			&& !extraneous.contains(b.getName()))
				return true;
		}		
		return false;
	}
	
	private boolean grounded(StartElement start) throws QueryEvaluationException {
		// all explicit variables or content originating from this element must be bound
		// These origins are the first use of a variable - no need to check subsequent use in descendants
		for (String name: resultSet.getBindingNames()) {
			TermOrigin origin = origins.get(name);
			if (origin==null) continue;
			// implicit vars (apart from CONTENT) need not be grounded
			if (isAnonymous(name) 
			&& !(origin.isTextContent() || origin.isBlankNode())) 
				continue;
			if (origin.pathEquals(context.path) && context.assignments.get(name)==null) 
				return false;
		}
		return true;
	}
	
	private boolean complete(StartElement start) throws QueryEvaluationException {
		// all implicit variables originating from this element must be bound
		// excluding property expressions
		for (String name: resultSet.getBindingNames()) {
			if (!isAnonymous(name) || extraneous.contains(name)) continue;
			TermOrigin value = origins.get(name);
			if (value == null) continue;
			if (value.pathEquals(context.path) && context.assignments.get(name)==null) 
				return false;
		}
		return true;
	}

	private boolean isAnonymous(String name) {
		TermOrigin origin = origins.get(name);
		return origin != null && origin.isAnonymous();
	}

	/* is the result set consistent with assignments to this point 
	 * variables tied to property expressions are not considered */
	
	private boolean consistent() {
		if (result==null) return true;
		for (Iterator<Binding> i=result.iterator(); i.hasNext();) {
			Binding b = i.next();
			TermOrigin value = origins.get(b.getName());
			if (value == null) continue;
			// is this a property expression with a curie
			if (value.isPropertyPresent()) continue;
			Value v = context.assignments.get(b.getName());
			if (v!=null && !b.getValue().equals(v)) return false;
		}
		return true;
	}

	/* An attribute is substitutable if it is an RDFa assignable attribute: "about", "resource", "typeof"
	 * or if it is the subject or object of a triple in this OR ANY SHALLOWER element
	 * (e.g. "href", "src"), with a variable value "?VAR".
	 * ANY other attribute value with the variable expression syntax {?VAR} is substitutable.
	 * RDFa @content to be added later.
	 * Returns the variable name or null. 
	 */
	
	Value substitute(StartElement start, String tag, Attribute attr,
			Location location, String path) throws RDFParseException,
			QueryEvaluationException {
		String namespace = attr.getName().getNamespaceURI();
		String localPart = attr.getName().getLocalPart();
		String value = attr.getValue();
		// primary RDFa object attribute, excluding 'content' and 'property'
		if ((namespace.isEmpty() && RDFA_OBJECTS.contains(localPart)) || tag.equals(BASE_TAG)) {
			if (value.startsWith("?")) {
				String var = value.substring(1);
				return context.assignments.get(var);
			}
			else return null;
		}
		// enumerate variables in triples with ?VAR syntax
		for (String name: resultSet.getBindingNames()) {
			if (context.assignments.get(name) != null 
			&& value.startsWith("?") && value.substring(1).equals(name)
			&& namespace.isEmpty() && RDFaVarAttributes.contains(localPart)) 
				return context.assignments.get(name);
		}
		// look for variable expressions in the attribute value
		value = ExpressionUtil.substitute(value, location, context.assignments, origins, start.getNamespaceContext());
		return value!=null?valueFactory.createLiteral(value):null;
	}
	
	// whitespace
	private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
	
	boolean isWhitespace(XMLEvent event) {
		if (event!=null && event.isCharacters()) {
			String text = event.asCharacters().getData();
			return WHITESPACE_PATTERN.matcher(text).matches();
		}
		return false;
	}
	
	/* Use result bindings to make assignments in this context */

	private Value assign(StartElement start) {
		if (result==null) return null;
		Value content = null;
		// identify implicit variables for this element, not found among the attributes
		for (Iterator<Binding> i=result.iterator(); i.hasNext();) {
			Binding b = i.next();
			TermOrigin value = origins.get(b.getName());
			if (value == null) continue;
			if (value.pathEquals(context.path)) {
				// context.property refers to CONTENT
				if (value.isTextContent())
					content = b.getValue();
				if (isAnonymous(b.getName())) {
					Value val = b.getValue();
					Value current = context.assignments.get(b.getName());
					if (current!=null) {
						// append multiple property values
						String append = current.stringValue() + " " + val.stringValue();
						val = valueFactory.createLiteral(append);
					}
					context.assignments.put(b.getName(), val);
					consumed.add(b);
				}
			}
		}
		// identify attributes that MAY contain RDFa variables,
		// if they contain a bound variable, add the assignment
		for (Iterator<?> i = start.getAttributes(); i.hasNext();) {
			Attribute attr = (Attribute) i.next();
			String namespace = attr.getName().getNamespaceURI();
			String localPart = attr.getName().getLocalPart();
			String value = attr.getValue();
			if (RDFaVarAttributes.contains(localPart)
			&& namespace.isEmpty() && value.startsWith("?")) {
				String name = attr.getValue().substring(1);
				Value v = result.getValue(name);
				if (v!=null) {
					context.assignments.put(name, v);
					consumed.add(result.getBinding(name));
				}
			}
		}
		return content;
	}
	
	String getPrefix(String namespace, NamespaceContext ctx) {
		if (namespace==null || ctx == null)
			return null;
		String prefix = ctx.getPrefix(namespace);
		// deal with problematic namespaces without trailing symbol
		if (prefix==null && namespace.endsWith("#")) 
			prefix = ctx.getPrefix(namespace.substring(0,namespace.length()-1));
		return prefix;
	}
	
	private URI getDatatype(Value content) {
		if (content instanceof Literal) {
			return ((Literal) content).getDatatype();
		}
		return null;
	}

	private String getDatatypeCurie(Value content, NamespaceContext ctx) {
		URI uri = getDatatype(content);
		String datatype = null;
		if (uri!=null) {
			// convert datatype URI into a curie
			String prefix = getPrefix(uri.getNamespace(), ctx);
			if (prefix==null) {
				datatype = uri.stringValue();
			} else {
				datatype = prefix+":"+uri.getLocalName();
			}
		}
		return datatype;
	}

	private String getLang(Value content) {
		if (content instanceof Literal) {
			return ((Literal) content).getLanguage();
		}
		return null;
	}
	
	/* an iterator over attributes - substituting variable bindings 
	 * Only assigns content to @content if the attribute is present
	 * May return more attributes than in the input, adding e.g. datatype and xml:lang
	 */
	
	class AttributeIterator implements Iterator<Object> {
		private final StartElement start;
		private final Iterator<?> attributes;
		private final Value content;
		private String datatype, lang;
		private final String path, tag;
		private Attribute nextAttribute;
		private final boolean hasBody;
		private final NamespaceContext ctx;
		
		public AttributeIterator
		(String tag, StartElement start, Value content, String path, boolean hasBody, NamespaceContext ctx) {
			this.tag = tag;
			this.start = start;
			this.attributes = start.getAttributes();
			this.content = content;
			this.path = path;
			this.hasBody = hasBody;
			this.ctx = ctx;
			datatype = getDatatypeCurie(content,ctx);
			lang = getLang(content);
			nextAttribute = more();
		}
		@Override
		public boolean hasNext() {
			return nextAttribute!=null;
		}
		@Override
		public Object next() {
			Attribute a = nextAttribute;
			nextAttribute = more();
			return a;
		}
		private Attribute more() {			
			if (attributes.hasNext()) {
				Attribute attr = (Attribute) attributes.next();
				String namespace = attr.getName().getNamespaceURI();
				String localPart = attr.getName().getLocalPart();
				
				Value newValue = substituteValue(start, tag,attr);
				if (newValue!=null) {
					// clear content to prevent it being added as text
					if (namespace.isEmpty() && localPart.equals("content")) {
						context.content = null;
						if (newValue instanceof Literal) {
							if (datatype == null) {
								datatype = getDatatypeCurie(newValue,ctx);
							}
							if (lang == null) {
								lang = getLang(newValue);
							}
						}
					}
					if (newValue instanceof BNode)
						return createAttribute(start, attr, "[_:" + newValue.stringValue() + "]");
					return createAttribute(start, attr, newValue.stringValue());
				}
				else return attr;
			}
			// opportunity to add additional attributes
			else if (datatype!=null) {
				Attribute a = createAttribute(start, new QName("datatype"), datatype);
				datatype = null;
				return a;
			}
			else if (lang!=null) {
				QName q = new QName(XML_NAMESPACE,"lang","xml");
				Attribute a = createAttribute(start, q, lang);
				lang = null;
				return a;
			}
			return null;
		}
		/* If there is a content attribute there is no need to add text content */
		private Value substituteValue(StartElement start, String tag, Attribute attr) {
			String namespace = attr.getName().getNamespaceURI();
			String localPart = attr.getName().getLocalPart();
			String value = attr.getValue();
			try {
				Value v = substitute(start, tag,attr,FallbackLocation.newInstance(attr, start), context.path);
				// content variables are currently represented as empty strings
				if (v==null && localPart.equals("content") && namespace.isEmpty() && value.isEmpty()) {
					// remove content from the context to prevent addition as text content
					context.content = null;
					return content;	
				}
				return v;
			} catch (RDFParseException e) {
				throw new UndeclaredThrowableException(e);
			} catch (QueryEvaluationException e) {
				throw new UndeclaredThrowableException(e);
			}
		}
		@Override
		public void remove() {
		}	
	}
	
	/* NamespaceIterator is able to add an additional namespace declaration for content */
	
	class NamespaceIterator implements Iterator<Namespace> {
		final StartElement start;
		final Iterator<?> namespaces;
		String namespaceURI, prefix;
		Namespace nextNamespace;
		public NamespaceIterator(StartElement start, Value content, NamespaceContext ctx) {
			this.start = start;
			this.namespaces = start.getNamespaces();
			URI datatype = getDatatype(content);
			if (datatype!=null) {
				namespaceURI = datatype.getNamespace();
				String p = getPrefix(namespaceURI,ctx);
				// if the namespace is already defined clear it
				if (p!=null) namespaceURI = null;
				else prefix = null;
			}
			nextNamespace = more();
		}
		public boolean hasNext() {
			return nextNamespace!=null;
		}
		public Namespace next() {
			Namespace ns = nextNamespace;
			nextNamespace = more();
			return ns;
		}
		private Namespace more() {
			if (namespaces.hasNext()) return (Namespace) namespaces.next();
			// if there is an undefined (null) prefix add xmlns:null
			if (namespaceURI!=null) {
				String ns = namespaceURI;
				// prevent adding this again
				namespaceURI = null;
				return createNamespace(start, prefix!=null?prefix:"null", ns);
			}
			return null;
		}
		public void remove() {
		}		
	}
	
	/* Add the start element, and content if the next event is not end element */
	
	private void addStartElement(StartElement start) throws XMLStreamException {
		QName name = start.getName();
		String tag = name.getNamespaceURI()+name.getLocalPart();
		// only add content if the body is empty or ignorable whitespace
		boolean hasBody = false;
		for (int i=reader.nextIndex(); !hasBody; i++) {
			XMLEvent e = input.get(i);
			if (e.isEndElement()) break;
			if (e.isCharacters() && e.toString().trim().isEmpty()) continue;
			hasBody = true;
		}
		NamespaceContext ctx = start.getNamespaceContext();
		Iterator<?> namespaces = new NamespaceIterator(start, context.content, ctx);
		// AttributeIterator may clear context.content on construction so do this last
		Iterator<?> attributes = new AttributeIterator(tag,start, context.content, context.path, hasBody, ctx);
		XMLEvent e = createStartElement(start, attributes, namespaces, ctx);
		add(e);
		
		// The AttributeIterator (above) clears context.content if it adds the content attribute
		if (!hasBody && context.content!=null)
			add(createCharacters(context.content.stringValue(), start.getLocation()));
	}

	public synchronized Namespace createNamespace(StartElement start, String string, String ns) {
		eventFactory.setLocation(start.getLocation());
		return eventFactory.createNamespace(string, ns);
	}

	private synchronized XMLEvent createStartElement(StartElement start,
			Iterator<?> attributes, Iterator<?> namespaces, NamespaceContext ctx) {
		QName name = start.getName();
		eventFactory.setLocation(start.getLocation());
		return eventFactory.createStartElement(name.getPrefix(),
				name.getNamespaceURI(), name.getLocalPart(), attributes,
				namespaces, ctx);
	}

	private synchronized Attribute createAttribute(StartElement start, QName name, String value) {
		eventFactory.setLocation(start.getLocation());
		return eventFactory.createAttribute(name, value);
	}

	private synchronized Attribute createAttribute(StartElement start, Attribute attr, String value) {
		eventFactory.setLocation(FallbackLocation.newInstance(attr, start));
		return eventFactory.createAttribute(attr.getName(), value);
	}

	private synchronized XMLEvent createCharacters(String text,
			Location location) {
		eventFactory.setLocation(location);
		return eventFactory.createCharacters(text);
	}

}
