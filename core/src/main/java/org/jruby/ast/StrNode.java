/*
 ***** BEGIN LICENSE BLOCK *****
 * Version: EPL 2.0/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Eclipse Public
 * License Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.eclipse.org/legal/epl-v20.html
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * Copyright (C) 2001-2002 Benoit Cerrina <b.cerrina@wanadoo.fr>
 * Copyright (C) 2001-2002 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2002 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * 
 * Alternatively, the contents of this file may be used under the terms of
 * either of the GNU General Public License Version 2 or later (the "GPL"),
 * or the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the EPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the EPL, the GPL or the LGPL.
 ***** END LICENSE BLOCK *****/

package org.jruby.ast;

import java.util.List;
import java.util.Objects;

import org.jruby.Ruby;
import org.jruby.ast.types.ILiteralNode;
import org.jruby.ast.visitor.NodeVisitor;
import org.jruby.ir.builder.StringStyle;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ByteList;
import org.jruby.util.StringSupport;

import static org.jruby.ir.builder.StringStyle.*;

/** 
 * Representing a simple String literal.
 */
public class StrNode extends Node implements ILiteralNode, LiteralValue, SideEffectFree {
    private final ByteList value;
    private final int codeRange;
    private StringStyle stringStyle;

    public StrNode(int line, ByteList value) {
        this(line, value, StringSupport.codeRangeScan(value.getEncoding(), value), Frozen);
    }

    public StrNode(int line, ByteList value, int codeRange, StringStyle stringStyle) {
        super(line, false);

        this.value = value;
        this.codeRange = codeRange;
        this.stringStyle = stringStyle;
    }

    public StrNode(int line, StrNode head, StrNode tail) {
        super(line, false);
        
        ByteList headBL = head.getValue();
        ByteList tailBL = tail.getValue();

        ByteList myValue = new ByteList(headBL.getRealSize() + tailBL.getRealSize());
        myValue.setEncoding(headBL.getEncoding());
        myValue.append(headBL);
        myValue.append(tailBL);

        // Convoluted logic and maybe not totally needed.  Frozen loses to anything else.
        // once we know neither is Frozen than a mismatch means we just use chilled.
        if (head.stringStyle != tail.stringStyle) {
            if (head.stringStyle == Frozen) {
                stringStyle = tail.stringStyle;
            } else if (tail.stringStyle == Frozen) {
                stringStyle = head.stringStyle;
            } else {
                stringStyle = Chilled;
            }
        } else {
            stringStyle = head.stringStyle;
        }

        value = myValue;
        codeRange = StringSupport.codeRangeScan(value.getEncoding(), value);
    }

    public NodeType getNodeType() {
        return NodeType.STRNODE;
    }
    /**
     * Accept for the visitor pattern.
     * @param iVisitor the visitor
     **/
    public <T> T accept(NodeVisitor<T> iVisitor) {
        return iVisitor.visitStrNode(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StrNode strNode = (StrNode) o;
        return value.equals(strNode.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    /**
     * Gets the value.
     * @return Returns a String
     */
    public ByteList getValue() {
        return value;
    }

    /**
     * Get the string's coderange.
     *
     * @return the string's coderange
     */
    public int getCodeRange() {
        return codeRange;
    }
    
    public List<Node> childNodes() {
        return EMPTY_LIST;
    }

    public StringStyle getStringStyle() {
        return stringStyle;
    }

    public void setStringStyle(StringStyle stringStyle) {
        this.stringStyle = stringStyle;
    }

    @Override
    public IRubyObject literalValue(Ruby runtime) {
        return runtime.newString(value);
    }
}
