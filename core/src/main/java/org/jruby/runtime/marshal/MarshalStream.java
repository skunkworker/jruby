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
 * Copyright (C) 2002-2004 Anders Bengtsson <ndrsbngtssn@yahoo.se>
 * Copyright (C) 2002-2004 Jan Arne Petersen <jpetersen@uni-bonn.de>
 * Copyright (C) 2004 Thomas E Enebo <enebo@acm.org>
 * Copyright (C) 2004 Charles O Nutter <headius@headius.com>
 * Copyright (C) 2004 Stefan Matthias Aust <sma@3plus4.de>
 * Copyright (C) 2006 Ola Bini <ola.bini@ki.se>
 * Copyright (C) 2007 William N Dortch <bill.dortch@gmail.com>
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

package org.jruby.runtime.marshal;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import org.jcodings.Encoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyBasicObject;
import org.jruby.RubyBignum;
import org.jruby.RubyBoolean;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyFloat;
import org.jruby.RubyHash;
import org.jruby.RubyModule;
import org.jruby.RubyRegexp;
import org.jruby.RubyString;
import org.jruby.RubyStruct;
import org.jruby.RubySymbol;
import org.jruby.runtime.ClassIndex;
import org.jruby.runtime.Constants;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.runtime.builtin.Variable;
import org.jruby.runtime.callsite.CacheEntry;
import org.jruby.util.ByteList;
import org.jruby.internal.runtime.methods.DynamicMethod;
import org.jruby.runtime.encoding.MarshalEncoding;

import static org.jruby.RubyBasicObject.getMetaClass;
import static org.jruby.api.Access.stringClass;
import static org.jruby.api.Convert.*;
import static org.jruby.api.Error.typeError;
import static org.jruby.runtime.marshal.MarshalCommon.*;
import static org.jruby.util.RubyStringBuilder.str;
import static org.jruby.util.RubyStringBuilder.types;

/**
 * Marshals objects into Ruby's binary marshal format.
 *
 * @author Anders
 */
@Deprecated(since = "10.0", forRemoval = true)
@SuppressWarnings("removal")
public class MarshalStream extends FilterOutputStream {
    private final Ruby runtime;
    private final MarshalCache cache;
    private final int depthLimit;
    private int depth = 0;

    public MarshalStream(Ruby runtime, OutputStream out, int depthLimit) throws IOException {
        super(out);

        this.runtime = runtime;
        this.depthLimit = depthLimit >= 0 ? depthLimit : Integer.MAX_VALUE;
        this.cache = new MarshalCache();

        out.write(Constants.MARSHAL_MAJOR);
        out.write(Constants.MARSHAL_MINOR);
    }

    public void dumpObject(IRubyObject value) throws IOException {
        depth++;
        
        if (depth > depthLimit) throw runtime.newArgumentError("exceed depth limit");


        writeAndRegister(value);

        depth--;
        if (depth == 0) out.flush(); // flush afer whole dump is complete
    }

    @Deprecated(since = "10.0")
    public void registerLinkTarget(IRubyObject newObject) {
        registerLinkTarget(((RubyBasicObject) newObject).getCurrentContext(), newObject);
    }

    public void registerLinkTarget(ThreadContext context, IRubyObject newObject) {
        if (shouldBeRegistered(context, newObject)) cache.register(newObject);
    }

    public void registerSymbol(ByteList sym) {
        cache.registerSymbol(sym);
    }

    static boolean shouldBeRegistered(ThreadContext context, IRubyObject value) {
        if (value.isNil()) {
            return false;
        } else if (value instanceof RubyBoolean) {
            return false;
        } else if (value instanceof RubyFixnum fixnum) {
            return ! isMarshalFixnum(context, fixnum);
        }
        return true;
    }

    private static boolean isMarshalFixnum(ThreadContext context, RubyFixnum fixnum) {
        var value = fixnum.getValue();
        return value <= RubyFixnum.MAX_MARSHAL_FIXNUM && value >= RubyFixnum.MIN_MARSHAL_FIXNUM;
    }

    private void writeAndRegisterSymbol(ByteList sym) throws IOException {
        if (cache.isSymbolRegistered(sym)) {
            cache.writeSymbolLink(this, sym);
        } else {
            registerSymbol(sym);
            dumpSymbol(sym);
        }
    }

    private void writeAndRegister(IRubyObject value) throws IOException {
        if (!(value instanceof RubySymbol) && cache.isRegistered(value)) {
            cache.writeLink(this, value);
        } else {
            value.getMetaClass().smartDump(this, value);
        }
    }

    private List<Variable<Object>> getVariables(IRubyObject value) throws IOException {
        List<Variable<Object>> variables = null;
        if (value instanceof CoreObjectType) {
            ClassIndex nativeClassIndex = ((CoreObjectType)value).getNativeClassIndex();
            
            if (nativeClassIndex != ClassIndex.OBJECT && nativeClassIndex != ClassIndex.BASICOBJECT) {
                if (shouldMarshalEncoding(value) || (
                        !value.isImmediate()
                        && value.hasVariables()
                        && nativeClassIndex != ClassIndex.CLASS
                        && nativeClassIndex != ClassIndex.MODULE
                        )) {
                    // object has instance vars and isn't a class, get a snapshot to be marshalled
                    // and output the ivar header here

                    variables = value.getMarshalVariableList();

                    // check if any of those variables were actually set
                    if (!variables.isEmpty() || shouldMarshalEncoding(value)) {
                        // write `I' instance var signet if class is NOT a direct subclass of Object
                        write(TYPE_IVAR);
                    } else {
                        // no variables, no encoding
                        variables = null;
                    }
                }
                final RubyClass meta = getMetaClass(value);
                RubyClass type = switch (nativeClassIndex) {
                    case STRING, REGEXP, ARRAY, HASH -> dumpExtended(meta);
                    default -> meta;
                };

                if (nativeClassIndex != meta.getClassIndex() &&
                        nativeClassIndex != ClassIndex.STRUCT &&
                        nativeClassIndex != ClassIndex.FIXNUM &&
                        nativeClassIndex != ClassIndex.BIGNUM) {
                    // object is a custom class that extended one of the native types other than Object
                    writeUserClass(value, type);
                }
            }
        }
        return variables;
    }

    private static boolean shouldMarshalEncoding(IRubyObject value) {
        if (!(value instanceof MarshalEncoding)) return false;
        return ((MarshalEncoding) value).shouldMarshalEncoding();
    }

    @Deprecated(since = "10.0")
    public void writeDirectly(IRubyObject value) throws IOException {
        writeDirectly(value.getRuntime().getCurrentContext(), value);
    }

    public void writeDirectly(ThreadContext context, IRubyObject value) throws IOException {
        List<Variable<Object>> variables = getVariables(value);
        writeObjectData(context, value);
        if (variables != null) {
            dumpVariablesWithEncoding(variables, value);
        }
    }

    public static String getPathFromClass(RubyModule clazz) {
        var context = clazz.getRuntime().getCurrentContext();
        RubyString path = clazz.rubyName(context);
        
        if (path.charAt(0) == '#') {
            String type = clazz.isClass() ? "class" : "module";
            throw typeError(context, str(context.runtime, "can't dump anonymous " + type + " ", types(context.runtime, clazz)));
        }
        
        RubyModule real = clazz.isModule() ? clazz : ((RubyClass)clazz).getRealClass();
        Ruby runtime = clazz.getRuntime();

        // FIXME: This is weird why we do this.  rubyName should produce something which can be referred so what example
        // will this fail on?  If there is a failing case then passing asJavaString may be broken since it will not be
        // a properly encoded string.  If this is an issue we should make a clazz.IdPath where all segments are returned
        // by their id names.
        if (runtime.getClassFromPath(path.asJavaString()) != real) {
            throw typeError(context, str(context.runtime, types(context.runtime, clazz), " can't be referred"));
        }
        return path.asJavaString();
    }
    
    private void writeObjectData(ThreadContext context, IRubyObject value) throws IOException {
        // switch on the object's *native type*. This allows use-defined
        // classes that have extended core native types to piggyback on their
        // marshalling logic.
        if (value instanceof CoreObjectType) {
            if (value instanceof DataType) {
                throw typeError(context, str(context.runtime, "no _dump_data is defined for class ",
                        types(context.runtime, getMetaClass(value))));
            }
            ClassIndex nativeClassIndex = ((CoreObjectType)value).getNativeClassIndex();

            switch (nativeClassIndex) {
            case ARRAY:
                write('[');
                RubyArray.marshalTo(context, (RubyArray<?>)value, this);
                return;
            case FALSE:
                write('F');
                return;
            case FIXNUM:
                RubyFixnum fixnum = (RubyFixnum)value;

                if (isMarshalFixnum(context, fixnum)) {
                    write('i');
                    writeInt(fixnum.asInt(context));
                    return;
                }
                // FIXME: inefficient; constructing a bignum just for dumping?
                value = RubyBignum.newBignum(context.runtime, fixnum.getValue());

                // fall through
            case BIGNUM:
                write('l');
                RubyBignum.marshalTo((RubyBignum)value, this);
                return;
            case CLASS:
                if (((RubyClass)value).isSingleton()) throw typeError(context, "singleton class can't be dumped");
                write('c');
                RubyClass.marshalTo((RubyClass)value, this);
                return;
            case FLOAT:
                write('f');
                RubyFloat.marshalTo((RubyFloat)value, this);
                return;
            case HASH: {
                RubyHash hash = (RubyHash)value;

                if(hash.getIfNone() == RubyBasicObject.UNDEF){
                    write('{');
                } else if (hash.hasDefaultProc()) {
                    throw typeError(context, "can't dump hash with default proc");
                } else {
                    write('}');
                }

                RubyHash.marshalTo(hash, this);
                return;
            }
            case MODULE:
                write('m');
                RubyModule.marshalTo((RubyModule)value, this);
                return;
            case NIL:
                write('0');
                return;
            case OBJECT:
            case BASICOBJECT:
                final RubyClass type = getMetaClass(value);
                dumpDefaultObjectHeader(context, type);
                type.getRealClass().marshal(value, this);
                return;
            case REGEXP:
                write('/');
                RubyRegexp.marshalTo((RubyRegexp)value, this);
                return;
            case STRING:
                registerLinkTarget(context, value);
                write('"');
                writeString(value.convertToString().getByteList());
                return;
            case STRUCT:
                RubyStruct.marshalTo((RubyStruct)value, this);
                return;
            case SYMBOL:
                writeAndRegisterSymbol(((RubySymbol) value).getBytes());
                return;
            case TRUE:
                write('T');
                return;
            default:
                throw typeError(context, str(context.runtime, "can't dump ", types(context.runtime, value.getMetaClass())));
            }
        } else {
            var metaClass = value.getMetaClass();
            dumpDefaultObjectHeader(context, metaClass);
            metaClass.getRealClass().marshal(value, this);
        }
    }

    @Deprecated(since = "10.0")
    public void userNewMarshal(IRubyObject value, CacheEntry entry) throws IOException {
        userNewMarshal(((RubyBasicObject) value).getCurrentContext(), value, entry);
    }

    public void userNewMarshal(ThreadContext context, IRubyObject value, CacheEntry entry) throws IOException {
        userNewCommon(context, value, entry);
    }

    @Deprecated(since = "10.0")
    public void userNewMarshal(IRubyObject value) throws IOException {
        userNewMarshal(((RubyBasicObject) value).getCurrentContext(), value);
    }

    public void userNewMarshal(ThreadContext context, IRubyObject value) throws IOException {
        userNewCommon(context, value, null);
    }

    private void userNewCommon(ThreadContext context, IRubyObject value, CacheEntry entry) throws IOException {
        registerLinkTarget(context, value);
        write(TYPE_USRMARSHAL);
        final RubyClass klass = getMetaClass(value);
        writeAndRegisterSymbol(asSymbol(context, klass.getRealClass().getName(context)).getBytes());

        IRubyObject marshaled;
        if (entry != null) {
            marshaled = entry.method.call(runtime.getCurrentContext(), value, entry.sourceModule, "marshal_dump");
        } else {
            marshaled = value.callMethod(runtime.getCurrentContext(), "marshal_dump");
        }
        if (marshaled.getMetaClass() == klass) {
            throw runtime.newRuntimeError("marshal_dump returned same class instance");
        }
        dumpObject(marshaled);
    }

    @Deprecated(since = "10.0")
    public void userMarshal(IRubyObject value, CacheEntry entry) throws IOException {
        userMarshal(((RubyBasicObject) value).getCurrentContext(), value, entry);
    }

    public void userMarshal(ThreadContext context, IRubyObject value, CacheEntry entry) throws IOException {
        userCommon(context, value, entry);
    }

    @Deprecated(since = "10.0")
    public void userMarshal(IRubyObject value) throws IOException {
        userMarshal(((RubyBasicObject) value).getCurrentContext(), value);
    }

    public void userMarshal(ThreadContext context, IRubyObject value) throws IOException {
        userCommon(context, value, null);
    }

    private void userCommon(ThreadContext context, IRubyObject value, CacheEntry entry) throws IOException {
        RubyFixnum depthLimitFixnum = asFixnum(context, depthLimit);
        final RubyClass klass = getMetaClass(value);
        IRubyObject dumpResult = entry != null ?
                entry.method.call(context, value, entry.sourceModule, "_dump", depthLimitFixnum) :
                value.callMethod(context, "_dump", depthLimitFixnum);
        RubyString marshaled = castAsString(context, dumpResult);

        List<Variable<Object>> variables = null;
        if (marshaled.hasVariables()) {
            variables = marshaled.getMarshalVariableList();
            if (!variables.isEmpty()) {
                write(TYPE_IVAR);
            } else {
                variables = null;
            }
        }

        write(TYPE_USERDEF);
        writeAndRegisterSymbol(asSymbol(context, klass.getRealClass().getName(context)).getBytes());
        writeString(marshaled.getByteList());

        if (variables != null) dumpVariables(variables);

        registerLinkTarget(context, value);
    }
    
    public void writeUserClass(IRubyObject obj, RubyClass type) throws IOException {
        var context = obj.getRuntime().getCurrentContext();
        write(TYPE_UCLASS);

        var className = type.getName(context);
        if (className.charAt(0) == '#') { // w_unique
            throw typeError(context, str(context.runtime, "can't dump anonymous class ", types(context.runtime, type)));
        }
        
        // w_symbol
        writeAndRegisterSymbol(asSymbol(context, className).getBytes());
    }
    
    public void dumpVariablesWithEncoding(List<Variable<Object>> vars, IRubyObject obj) throws IOException {
        if (shouldMarshalEncoding(obj)) {
            writeInt(vars.size() + 1); // vars preceded by encoding
            writeEncoding(((MarshalEncoding)obj).getMarshalEncoding());
        } else {
            writeInt(vars.size());
        }
        
        dumpVariablesShared(vars);
    }

    public void dumpVariables(List<Variable<Object>> vars) throws IOException {
        writeInt(vars.size());
        dumpVariablesShared(vars);
    }

    private void dumpVariablesShared(List<Variable<Object>> vars) throws IOException {
        for (Variable<Object> var : vars) {
            if (var.getValue() instanceof IRubyObject) {
                writeAndRegisterSymbol(RubySymbol.newSymbol(runtime, var.getName()).getBytes());
                dumpObject((IRubyObject)var.getValue());
            }
        }
    }

    public void writeEncoding(Encoding encoding) throws IOException {
        writeEncoding(runtime.getCurrentContext(), encoding);
    }

    public void writeEncoding(ThreadContext context, Encoding encoding) throws IOException {
        if (encoding == null || encoding == USASCIIEncoding.INSTANCE) {
            writeAndRegisterSymbol(asSymbol(context, SYMBOL_ENCODING_SPECIAL).getBytes());
            writeObjectData(context, context.fals);
        } else if (encoding == UTF8Encoding.INSTANCE) {
            writeAndRegisterSymbol(asSymbol(context, SYMBOL_ENCODING_SPECIAL).getBytes());
            writeObjectData(context, context.tru);
        } else {
            writeAndRegisterSymbol(asSymbol(context, SYMBOL_ENCODING).getBytes());
            RubyString encodingString = new RubyString(context.runtime, stringClass(context), encoding.getName());
            writeObjectData(context, encodingString);
        }
    }
    
    private boolean hasSingletonMethods(RubyClass type) {
        for(DynamicMethod method : type.getMethods().values()) {
            // We do not want to capture cached methods
            if(method.isImplementedBy(type)) {
                return true;
            }
        }
        return false;
    }

    /** w_extended
     * 
     */
    private RubyClass dumpExtended(RubyClass type) throws IOException {
        var context = type.getRuntime().getCurrentContext();
        if (type.isSingleton()) {
            if (hasSingletonMethods(type) || type.hasVariables()) { // any ivars, since we don't have __attached__ ivar now
                throw typeError(context, "singleton can't be dumped");
            }
            type = type.getSuperClass();
        }
        while (type.isIncluded()) {
            write('e');
            writeAndRegisterSymbol(asSymbol(context, type.getOrigin().getName(context)).getBytes());
            type = type.getSuperClass();
        }
        return type;
    }

    @Deprecated(since = "10.0")
    public void dumpDefaultObjectHeader(RubyClass type) throws IOException {
        dumpDefaultObjectHeader(runtime.getCurrentContext(), type);
    }

    public void dumpDefaultObjectHeader(ThreadContext context, RubyClass type) throws IOException {
        dumpDefaultObjectHeader(context, 'o',type);
    }

    @Deprecated(since = "10.0")
    public void dumpDefaultObjectHeader(char tp, RubyClass type) throws IOException {
        dumpDefaultObjectHeader(type.getRuntime().getCurrentContext(), tp, type);
    }

    public void dumpDefaultObjectHeader(ThreadContext context, char tp, RubyClass type) throws IOException {
        dumpExtended(type);
        write(tp);
        writeAndRegisterSymbol(asSymbol(context, getPathFromClass(type.getRealClass())).getBytes());
    }

    public void writeString(String value) throws IOException {
        writeInt(value.length());
        // FIXME: should preserve unicode?
        out.write(RubyString.stringToBytes(value));
    }

    public void writeString(ByteList value) throws IOException {
        int len = value.length();
        writeInt(len);
        out.write(value.getUnsafeBytes(), value.begin(), len);
    }

    public void dumpSymbol(ByteList value) throws IOException {
        write(':');
        int len = value.length();
        writeInt(len);
        out.write(value.getUnsafeBytes(), value.begin(), len);
    }

    public void writeInt(int value) throws IOException {
        if (value == 0) {
            out.write(0);
        } else if (0 < value && value < 123) {
            out.write(value + 5);
        } else if (-124 < value && value < 0) {
            out.write((value - 5) & 0xff);
        } else {
            byte[] buf = new byte[4];
            int i = 0;
            for (; i < buf.length; i++) {
                buf[i] = (byte)(value & 0xff);
                
                value = value >> 8;
                if (value == 0 || value == -1) {
                    break;
                }
            }
            int len = i + 1;
            out.write(value < 0 ? -len : len);
            out.write(buf, 0, i + 1);
        }
    }

    public void writeByte(int value) throws IOException {
        out.write(value);
    }

    @Deprecated
    public boolean isTainted() {
        return false;
    }

    @Deprecated
    public boolean isUntrusted() {
        return false;
    }
}
