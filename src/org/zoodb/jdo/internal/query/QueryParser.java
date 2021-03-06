/*
 * Copyright 2009-2013 Tilmann Zaeschke. All rights reserved.
 * 
 * This file is part of ZooDB.
 * 
 * ZooDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * ZooDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with ZooDB.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * See the README and COPYING files for further information. 
 */
package org.zoodb.jdo.internal.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import javax.jdo.JDOUserException;

import org.zoodb.jdo.internal.ZooClassDef;
import org.zoodb.jdo.internal.ZooFieldDef;


/**
 * The query parser. This class builds a query tree from a query string.
 * The tree consists of QueryTerms (comparative statements) and QueryNodes (logical operations on
 * two children (QueryTerms or QueryNodes).
 * The root of the tree is a QueryNode. QueryNodes may have only a single child.  
 * 
 * Negation is implemented by simply negating all operators inside the negated term.
 * 
 * TODO QueryOptimiser:
 * E.g. "((( A==B )))"Will create something like Node->Node->Node->Term. Optimise this to 
 * Node->Term. That means pulling up all terms where the parent node has no other children. The
 * only exception is the root node, which is allowed to have only one child.
 * 
 * @author Tilmann Zaeschke
 */
public final class QueryParser {

	static final Object NULL = new Object();
	
	private int pos = 0;
	private final String str;
	private final ZooClassDef clsDef;
	private final Map<String, ZooFieldDef> fields;
	private final  List<QueryParameter> parameters;
	
	public QueryParser(String query, ZooClassDef clsDef, List<QueryParameter> parameters) {
		this.str = query; 
		this.clsDef = clsDef;
		this.fields = clsDef.getAllFieldsAsMap();
		this.parameters = parameters;
	}
	
	private void trim() {
		while (!isFinished() && isWS(charAt0())) {
			pos++;
		}
	}
	
	/**
	 * @param c
	 * @return true if c is a whitespace character
	 */
	private boolean isWS(char c) {
		return c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f';
	}
	
	private char charAt0() {
		return str.charAt(pos);
	}
	
	private char charAt(int i) {
		return str.charAt(pos + i);
	}
	
	private void inc() {
		pos++;
	}
	
	private void inc(int i) {
		pos += i;
	}
	
	private int pos() {
		return pos;
	}
	
	private boolean isFinished() {
		return !(pos < str.length());
	}
	
	/**
	 * 
	 * @param ofs
	 * @return Whether the string is finished after the givven offset
	 */
	private boolean isFinished(int ofs) {
		return !(pos + ofs < str.length());
	}
	
	/**
	 * @return remaining length.
	 */
	private int len() {
		return str.length() - pos;
	}

	
	/**
	 * 
	 * @param pos0 start, absolute position, inclusive
	 * @param pos1 end, absolute position, exclusive
	 * @return sub-String
	 */
	private String substring(int pos0, int pos1) {
		return str.substring(pos0, pos1);
	}
	
	public QueryTreeNode parseQuery() {
		//Negation is used to invert negated operand.
		//We just pass it down the tree while parsing, always inverting the flag if a '!' is
		//encountered. When popping out of a function, the flag is reset to the value outside
		//the term that was parsed in a function. Actually, it is not reset, it is never modified.
		boolean negate = false;
		QueryTreeNode qn = parseTree(negate);
		while (!isFinished()) {
			qn = parseTree(null, qn, negate);
		}
		return qn;
	}
	
	private QueryTreeNode parseTree(boolean negate) {
		trim();
		while (charAt0() == '!') {
			negate = !negate;
			inc(LOG_OP.NOT._len);
			trim();
		}
		QueryTerm qt1 = null;
		QueryTreeNode qn1 = null;
		if (charAt0() == '(') {
			inc();
			qn1 = parseTree(negate);
			trim();
		} else {
			qt1 = parseTerm(negate);
		}

		if (isFinished()) {
			return new QueryTreeNode(qn1, qt1, null, null, null, negate).relateToChildren();
		}
		
		return parseTree(qt1, qn1, negate);
	}
	
	private QueryTreeNode parseTree(QueryTerm qt1, QueryTreeNode qn1, boolean negate) {
		trim();

		//parse log op
		char c = charAt0();
        if (c == ')') {
            inc(1);
            trim();
            if (qt1 == null) {
                return qn1;
            } else {
                return new QueryTreeNode(qn1, qt1, null, null, null, negate);
            }
        }
		char c2 = charAt(1);
		char c3 = charAt(2);
		LOG_OP op = null;
        if (c == '&' && c2 ==  '&') {
			op = LOG_OP.AND;
		} else if (c == '|' && c2 ==  '|') {
            op = LOG_OP.OR;
		} else if (substring(pos, pos+10).toUpperCase().equals("PARAMETERS")) {
			inc(10);
			trim();
			parseParameters();
			if (qt1 == null) {
				return qn1;
			} else {
				return new QueryTreeNode(qn1, qt1, null, null, null, negate);
			}
		} else if (substring(pos, pos+9).toUpperCase().equals("VARIABLES")) {
			throw new UnsupportedOperationException("JDO feature not supported: VARIABLES");
		} else if (substring(pos, pos+7).toUpperCase().equals("IMPORTS")) {
			throw new UnsupportedOperationException("JDO feature not supported: IMPORTS");
		} else if (substring(pos, pos+8).toUpperCase().equals("GROUP BY")) {
			throw new UnsupportedOperationException("JDO feature not supported: GROUP BY");
		} else if (substring(pos, pos+8).toUpperCase().equals("ORDER BY")) {
			throw new UnsupportedOperationException("JDO feature not supported: ORDER BY");
		} else if (substring(pos, pos+5).toUpperCase().equals("RANGE")) {
			throw new UnsupportedOperationException("JDO feature not supported: RANGE");
		} else {
			throw new JDOUserException(
					"Unexpected characters: '" + c + c2 + c3 + "' at: " + pos());
		}
		inc( op._len );
		trim();

		//check negations
		boolean negateNext = negate;
		while (charAt0() == '!') {
			negateNext = !negateNext;
			inc(LOG_OP.NOT._len);
			trim();
		}
		
		// read next term
		QueryTerm qt2 = null;
		QueryTreeNode qn2 = null;
		if (charAt0() == '(') {
			inc();
			qn2 = parseTree(negateNext);
			trim();
		} else {
			qt2 = parseTerm(negateNext);
		}

		return new QueryTreeNode(qn1, qt1, op, qn2, qt2, negate);
	}

	private QueryTerm parseTerm(boolean negate) {
		trim();
		Object value = null;
		String paramName = null;
		COMP_OP op = null;
		String fName = null;
		Class<?> type = null;

		int pos0 = pos();

		//read field name
		char c = charAt0();
		while ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') 
				|| (c=='_') || (c=='.')) {
			inc();
			if (c=='.') {
				String dummy = substring(pos0, pos());
				if (dummy.equals("this.")) {
					//System.out.println("STUB QueryParser.parseTerm(): Ignoring 'this.'.");
					pos0 = pos();
				} else {
					fName = substring(pos0, pos()-1);
					pos0 = pos();
					//TODO
//					if (startsWith("")) {
//
//					} else if (startsWith("")) {
//						
//					} else {
//						throw new JDOUserException("Can not parse query at position " + pos0 + 
//								": " + dummy);
//					}
				}
			}
			c = charAt0();
		}
		if (fName == null) {
			fName = substring(pos0, pos());
		}
		if (fName.equals("")) {
			throw new JDOUserException("Can not parse query at position " + pos0 + ": '" + c +"'");
		}
		pos0 = pos();
		trim();

		try {
			ZooFieldDef f = fields.get(fName);
			if (f == null) {
				throw new JDOUserException(
						"Field name not found: '" + fName + "' in " + clsDef.getClassName());
			}
			type = f.getJavaType();
		} catch (SecurityException e) {
			throw new JDOUserException("Field not accessible: " + fName, e);
		}


		//read operator
		c = charAt0();
		char c2 = charAt(1);
		char c3 = charAt(2);
		if (c == '=' && c2 ==  '=') {
			op = COMP_OP.EQ;
		} else if (c == '<') {
			if (c2 == '=') {
				op = COMP_OP.LE;
			} else {
				op = COMP_OP.L;
			}
		} else if (c == '>') {
			if (c2 ==  '=') {
				op = COMP_OP.AE;
			} else {
				op = COMP_OP.A;
			}
		} else if (c == '!' && c2 == '=') {
			op = COMP_OP.NE;
		}
		if (op == null) {
			throw new JDOUserException(
					"Unexpected characters: '" + c + c2 + c3 + "' at: " + pos0);
		}
		inc( op._len );
		trim();
		pos0 = pos();
	
		//read value
		c = charAt0();
		if ((len() >= 4 && substring(pos0, pos0+4).equals("null")) &&
				(len() == 4 || (len()>4 && (charAt(4) == ' ' || charAt(4) == ')')))) {  //hehehe :-)
			if (type.isPrimitive()) {
				throw new JDOUserException("Cannot compare 'null' to primitive at pos:" + pos0);
			}
			value = NULL;
			inc(4);
		} else if (c=='"' || c=='\'') {
			//According to JDO 2.2 14.6.2, String and single characters can both be delimited by 
			//both single and double quotes.
			boolean singleQuote = c == '\''; 
			//TODO allow char type!
			if (!String.class.isAssignableFrom(type)) {
				throw new JDOUserException("Incompatible types, found String, expected: " + 
						type.getName());
			}
			inc();
			pos0 = pos();
			c = charAt0();
			while (true) {
				if ( (!singleQuote && c=='"') || (singleQuote && c=='\'')) {
					break;
				} else if (c=='\\') {
					inc();
					if (isFinished(pos()+1)) {
						throw new JDOUserException("Try using \\\\\\\\ for double-slashes.");
					}
				}					
				inc();
				c = charAt0();
			}
			value = substring(pos0, pos());
			inc();
		} else if (c=='-' || (c >= '0' && c <= '9')) {
			pos0 = pos();
			boolean isHex = false;
			while (!isFinished()) {
				c = charAt0();
				if (c==')' || isWS(c) || c=='|' || c=='&') {
					break;
//				} else if (c=='.') {
//					isDouble = true;
//				} else if (c=='L' || c=='l') {
//					//if this is not at the last position, then we will fail later anyway
//					isLong = true;
				} else if (c=='x') {
					//if this is not at the second position, then we will fail later anyway
					isHex = true;
				}
				inc();
			}
			if (type == Double.TYPE || type == Double.class) {
				value = Double.parseDouble( substring(pos0, pos()) );
			} else if (type == Float.TYPE || type == Float.class) {
				value = Float.parseFloat( substring(pos0, pos()) );
			} else if (type == Long.TYPE || type == Long.class) {
				if (isHex) {
					value = Long.parseLong( substring(pos0+2, pos()), 16 );
				} else {
					value = Long.parseLong( substring(pos0, pos()));
				}
			} else if (type == Integer.TYPE || type == Integer.class) {
				if (isHex) {
					value = Integer.parseInt( substring(pos0+2, pos()), 16 );
				} else {
					value = Integer.parseInt( substring(pos0, pos()) );
				}
			} else if (type == Short.TYPE || type == Short.class) {
				if (isHex) {
					value = Short.parseShort( substring(pos0+2, pos()), 16 );
				} else {
					value = Short.parseShort( substring(pos0, pos()) );
				}
			} else if (type == Byte.TYPE || type == Byte.class) {
				if (isHex) {
					value = Byte.parseByte( substring(pos0+2, pos()), 16 );
				} else {
					value = Byte.parseByte( substring(pos0, pos()) );
				}
			} else if (type == BigDecimal.class) {
				value = new BigDecimal( substring(pos0+2, pos()) );
			} else if (type == BigInteger.class) {
				value = new BigInteger( substring(pos0, pos()) );
			} else { 
				throw new JDOUserException("Incompatible types, found number, expected: " + 
						type.getName());
			}
		} else if (type == Boolean.TYPE || type == Boolean.class) {
			if (substring(pos0, pos0+4).toLowerCase().equals("true") 
					&& (isFinished(4) || charAt(4)==' ' || charAt(4)==')')) {
				value = true;
				inc(4);
			} else if (substring(pos0, pos0+5).toLowerCase().equals("false") 
					&& (isFinished(5) || charAt(5)==' ' || charAt(5)==')')) {
				value = false;
				inc(5);
			} else {
			throw new JDOUserException("Incompatible types, expected Boolean, found: " + 
					substring(pos0, pos0+5));
			}
		} else {
			boolean isImplicit = false;
			if (c==':') {
				//implicit paramter
				isImplicit = true;
				inc();
				pos0 = pos;
				c = charAt0();
			}
			while ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c=='_')) {
				inc();
				if (isFinished()) break;
				c = charAt0();
			}
			paramName = substring(pos0, pos());
			if (isImplicit) {
				addParameter(type.getName(), paramName);
			} else {
				addParameter(null, paramName);
			}
		}
		if (fName == null || (value == null && paramName == null) || op == null) {
			throw new JDOUserException("Can not parse query at " + pos() + ": " + str);
		}
		trim();
		
		return new QueryTerm(op, paramName, value, clsDef.getField(fName), negate);
	}

	enum COMP_OP {
		EQ(2, false, false, true), 
		NE(2, true, true, false), 
		LE(2, true, false, true), 
		AE(2, false, true, true), 
		L(1, true, false, false), 
		A(1, false, true, false);

		private final int _len;
        private final boolean _allowsLess;
        private final boolean _allowsMore;
        private final boolean _allowsEqual;

		private COMP_OP(int len, boolean al, boolean am, boolean ae) {
			_len = len;
            _allowsLess = al; 
            _allowsMore = am; 
            _allowsEqual = ae; 
		}
        
		//TODO use in lines 90-110. Also use as first term(?).
        private boolean allowsLess() {
            return _allowsLess;
        }
        
        private boolean allowsMore() {
            return _allowsMore;
        }
        
        private boolean allowsEqual() {
            return _allowsEqual;
        }
        
        COMP_OP inverstIfTrue(boolean inverse) {
        	if (!inverse) {
        		return this;
        	}
        	switch (this) {
        	case EQ: return NE;
        	case NE: return EQ;
        	case LE: return A;
        	case AE: return L;
        	case L: return AE;
        	case A: return LE;
        	default: throw new IllegalArgumentException();
        	}
        }
	}

	/**
	 * Logical operators.
	 */
	enum LOG_OP {
		AND(2), // && 
		OR(2),  // ||
		//XOR(2);  
		NOT(1);  //TODO e.g. not supported in unary-stripper or in index-advisor

		private final int _len;

		private LOG_OP(int len) {
			_len = len;
		}
		LOG_OP inverstIfTrue(boolean inverse) {
			if (!inverse) {
				return this;
			}
			switch (this) {
			case AND: return OR;
			case OR: return AND;
			default: throw new IllegalArgumentException();
			}
		}
	}

	private void parseParameters() {
		while (!isFinished()) {
			char c = charAt0();
			int pos0 = pos;
			while ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c=='_') || (c=='.')) {
				inc();
				if (isFinished()) break;
				c = charAt0();
			}
			String typeName = substring(pos0, pos());
	
			//TODO check here for
			//IMPORTS
			//GROUP_BY
			//ORDER_BY
			//RANGE
			//TODO .. and implement according sub-methods
			
			trim();
			c = charAt0();
			pos0 = pos;
			while ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (c=='_')) {
				inc();
				if (isFinished()) break;
				c = charAt0();
			}
			String paramName = substring(pos0, pos());
			updateParameterType(typeName, paramName);
			trim();
		}
	}
	
	private void addParameter(String type, String name) {
		for (QueryParameter p: parameters) {
			if (p.getName().equals(name)) {
				throw new JDOUserException("Duplicate parameter name: " + name);
			}
		}
		this.parameters.add(new QueryParameter(type, name));
	}
	
	private void updateParameterType(String type, String name) {
		for (QueryParameter p: parameters) {
			if (p.getName().equals(name)) {
				if (p.getType() != null) {
					throw new JDOUserException("Duplicate parameter name: " + name);
				}
				p.setType(type);
				return;
			}
		}
		throw new JDOUserException("Parameter not used in query: " + name);
	}

}
