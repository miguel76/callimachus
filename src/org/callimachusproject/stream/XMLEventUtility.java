package org.callimachusproject.stream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.Characters;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

public class XMLEventUtility {
	private static final String LINE_BREAK = "\n";
	private static final String DOCTYPE = "<!DOCTYPE html>";

	public static InputStream toInputStream(final XMLEventReader xml) throws Exception {
		return new InputStream() {
			byte[] buffer;
			int index;
			boolean skipNext;
			public int read() throws IOException {
				if (buffer!=null && index<buffer.length) return buffer[index++];
				else while (xml.hasNext()) {
					if (skipNext) {
						xml.next();
						skipNext = false;
						continue;
					}
					StringBuffer b = new StringBuffer();
					try {
						skipNext = serialize(b, (XMLEvent) xml.next(), xml.peek());
						buffer = b.toString().getBytes("UTF8");
					} catch (XMLStreamException x) {
						x.printStackTrace();
					}
					index = 0;
					if (buffer.length>0) return buffer[index++];
				}
				return -1;
			}
		};
	}
	
	private static boolean serialize(StringBuffer buffer, XMLEvent e, XMLEvent peek) {
		if (e.isStartDocument()) {
			buffer.append(DOCTYPE);
		}
		else if (e.isStartElement()) {
			StartElement start = e.asStartElement();
			buffer.append("<");
			serialize(buffer,start.getName());
			serialize(buffer,start.getAttributes());
			if (peek.isEndElement()) buffer.append("/");
			buffer.append(">");
			if (peek.isCharacters() || peek.isStartElement()) return false;
			if (peek.isEndElement()) return true;
		}
		else if (e.isEndElement()) {
			EndElement end = e.asEndElement();
			buffer.append("</");
			serialize(buffer,end.getName());
			buffer.append(">");
			if (peek.isEndElement()) return false;
		}
		else if (e.isCharacters()) {
			Characters chars = e.asCharacters();
			//if (chars.isCData()) buffer.append("<![CDATA[");
			String data = chars.getData();
			data = substituteEntitiesInBody(data);
			buffer.append(data.trim());
			//if (chars.isCData()) buffer.append("]]>");
			// skip line break
			return false;
		}
		else if (e.isEntityReference()) {
			buffer.append(e.toString());
			// skip line break
			return false;			
		}
		else if (e.getEventType()==XMLEvent.COMMENT) {
			buffer.append(e.toString());
		}
		buffer.append(LINE_BREAK);
		return false;
	}

	private static String substituteEntitiesInBody(String data) {
		return data
			.replaceAll("\\u0026", "&#x26;") // amp (do this first)
			.replaceAll("\\u003c", "&#x3C;") // lt
			.replaceAll("\\u003E", "&#x3E;") // gt
			.replaceAll("\\u00A0", "&#xA0;"); // nbsp
	}
	
	private static String substituteEntitiesinValue(String data) {
		return data
			.replaceAll("\\u0026", "&#x26;") // amp
			.replaceAll("\\u0022", "&#x22;") // quot
			.replaceAll("\\u0027", "&#x27;") // apos
			.replaceAll("\\u00A0", "&#xA0;"); // nbsp
	}


	private static void serialize(StringBuffer buffer, Iterator<?> attributes) {
		while (attributes.hasNext()) {
			Attribute attr = (Attribute) attributes.next();
			buffer.append(" ");
			serialize(buffer, attr.getName());
			buffer.append("=");
			buffer.append("\""+substituteEntitiesinValue(attr.getValue())+"\"");
		}
	}

	private static void serialize(StringBuffer buffer, QName qname) {
		String prefix = qname.getPrefix();
		if (!prefix.isEmpty()) buffer.append(prefix+":"+qname.getLocalPart());
		else buffer.append(qname.getLocalPart());
	}	
	

}
