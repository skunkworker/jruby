/***** BEGIN LICENSE BLOCK *****
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
 * Copyright (C) 2006 Kresten Krab Thorup <krab@gnu.org>
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

package org.jruby.javasupport.proxy;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import org.jruby.Ruby;
import org.jruby.RubyArray;
import org.jruby.RubyClass;
import org.jruby.RubyFixnum;
import org.jruby.RubyModule;
import org.jruby.RubyProc;
import org.jruby.RubyString;
import org.jruby.anno.JRubyClass;
import org.jruby.anno.JRubyMethod;
import org.jruby.exceptions.RaiseException;
import org.jruby.java.invokers.RubyToJavaInvoker;
import org.jruby.javasupport.Java;
import org.jruby.javasupport.ParameterTypes;
import org.jruby.runtime.Helpers;
import org.jruby.runtime.Arity;
import org.jruby.runtime.Block;
import org.jruby.runtime.ThreadContext;
import org.jruby.runtime.builtin.IRubyObject;
import org.jruby.util.ArraySupport;

import static org.jruby.api.Convert.asFixnum;
import static org.jruby.api.Create.newString;
import static org.jruby.api.Error.argumentError;
import static org.jruby.javasupport.JavaCallable.inspectParameterTypes;
import static org.jruby.runtime.ObjectAllocator.NOT_ALLOCATABLE_ALLOCATOR;

@JRubyClass(name="Java::JavaProxyConstructor")
public class JavaProxyConstructor extends JavaProxyReflectionObject implements ParameterTypes {

    private final Constructor<?> proxyConstructor;
    private final Class<?>[] actualParameterTypes;
    private final boolean actualVarArgs;
    private final boolean exportable; //exportable to java, or: does it have the jruby Ruby+RubyClass classes at the end?

    private final JavaProxyClass declaringProxyClass;

    public static RubyClass createJavaProxyConstructorClass(ThreadContext context, RubyClass Object, RubyModule Java) {
        var JavaProxyConstructor = (RubyClass) Java.defineClassUnder(context, "JavaProxyConstructor", Object, NOT_ALLOCATABLE_ALLOCATOR).
                defineMethods(context, JavaProxyConstructor.class);

        JavaProxyReflectionObject.registerRubyMethods(context, JavaProxyConstructor);

        return JavaProxyConstructor;

    }

    JavaProxyConstructor(Ruby runtime, JavaProxyClass proxyClass, Constructor<?> constructor) {
        super(runtime, runtime.getJavaSupport().getJavaProxyConstructorClass());
        this.declaringProxyClass = proxyClass;
        this.proxyConstructor = constructor;
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        this.exportable = parameterTypes.length == 0 || parameterTypes[parameterTypes.length - 1] != RubyClass.class;
        // see ruby constructors, last two are Ruby,RubyClass for generated/reified constructors (RubyClass/reifiy)
        this.actualParameterTypes = ArraySupport.newCopy(parameterTypes, parameterTypes.length - (exportable?0:2));
        this.actualVarArgs = proxyConstructor.isVarArgs();
    }

    public final Class<?>[] getParameterTypes() {
        return actualParameterTypes;
    }

    public final Class<?>[] getExceptionTypes() {
        return proxyConstructor.getExceptionTypes();
    }

    public final boolean isExportable() {
        return exportable;
    }

    public final boolean isVarArgs() { return actualVarArgs; }

    @JRubyMethod(name = "declaring_class")
    public JavaProxyClass getDeclaringClass() {
        return declaringProxyClass;
    }

    public final Object newInstance(Object[] args, Ruby runtime, IRubyObject clazz)
        throws IllegalArgumentException, InstantiationException,
               IllegalAccessException, InvocationTargetException {
        final int len = args.length;
        if ( len != actualParameterTypes.length ) {
            throw new IllegalArgumentException("wrong number of parameters");
        }
        if (exportable)
        	return newInstanceImpl(args, null, null);
        else
        	return newInstanceImpl(ArraySupport.newCopy(args, len + 2), runtime, clazz); // does args[ len ] = handler;
    }

    /**
     * For exportable objects, argsPlus1 is not plus one
     * 
     * @param argsPlus1
     * @param runtime
     * @param clazz
     * @return
     * @throws IllegalArgumentException
     * @throws InstantiationException
     * @throws IllegalAccessException
     * @throws InvocationTargetException
     */
    final Object newInstanceImpl(Object[] argsPlus1, Ruby runtime, IRubyObject clazz)
            throws IllegalArgumentException, InstantiationException, IllegalAccessException, InvocationTargetException {
        if (!exportable) {
            argsPlus1[argsPlus1.length - 2] = runtime;
            argsPlus1[argsPlus1.length - 1] = clazz;
        }
        return proxyConstructor.newInstance(argsPlus1);
    }

    @Deprecated(since = "10.0")
    public RubyFixnum arity() {
        return arity(getCurrentContext());
    }

    @JRubyMethod
    public RubyFixnum arity(ThreadContext context) {
        return asFixnum(context, getArity());
    }

    public final int getArity() {
        return getParameterTypes().length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JavaProxyConstructor &&
            this.proxyConstructor == ((JavaProxyConstructor)other).proxyConstructor;
    }

    @Override
    public int hashCode() {
        return proxyConstructor.hashCode();
    }

    @Deprecated(since = "10.0")
    public RubyString inspect() {
        return inspect(getCurrentContext());
    }

    @Override
    @JRubyMethod
    public RubyString inspect(ThreadContext context) {
        return newString(context, toString());
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("#<");
        buf.append( getDeclaringClass().nameOnInspection() );
        inspectParameterTypes(buf, this);
        buf.append('>');
        return buf.toString();
    }

    @Deprecated(since = "10.0")
    public final RubyArray argument_types() {
        return argument_types(getCurrentContext());
    }

    @JRubyMethod
    public final RubyArray argument_types(ThreadContext context) {
        return toClassArray(context, getParameterTypes());
    }

    @JRubyMethod(rest = true)
    public IRubyObject new_instance2(ThreadContext context, IRubyObject[] args, Block unusedBlock) {
        final Ruby runtime = context.runtime;
        Arity.checkArgumentCount(context, args, 2, 2);

        final IRubyObject self = args[0];
        final Object[] convertedArgs = convertArguments((RubyArray) args[1]); // constructor arguments

        try {
            return wrapJavaObject(runtime, newInstance(convertedArgs, runtime, self));
        }
        catch (Exception ex) { throw mapInstantiationException(runtime, ex); }
    }

    public final IRubyObject newInstance(final Ruby runtime, final IRubyObject self, Object[] args) throws RaiseException {
        try {
            return wrapJavaObject(runtime, newInstance(args, runtime, self));
        }
        catch (Exception ex) { throw mapInstantiationException(runtime, ex); }
    }

    public final IRubyObject newInstance(final Ruby runtime, final IRubyObject self, IRubyObject[] args) throws RaiseException {
        final Object[] javaArgsPlus1 = RubyToJavaInvoker.convertArguments(this, args, (exportable?0:+2));

        try {
            return wrapJavaObject(runtime, newInstanceImpl(javaArgsPlus1, runtime, self));
        }
        catch (Exception ex) { throw mapInstantiationException(runtime, ex); }
    }

    public final IRubyObject newInstance(final Ruby runtime, final IRubyObject self, IRubyObject arg0) throws RaiseException {
        final Object[] javaArgsPlus1 = RubyToJavaInvoker.convertArguments(this, arg0, (exportable?0:+2));

        try {
            return wrapJavaObject(runtime, newInstanceImpl(javaArgsPlus1, runtime, self));
        }
        catch (Exception ex) { throw mapInstantiationException(runtime, ex); }
    }

    private static IRubyObject wrapJavaObject(final Ruby runtime, final Object obj) {
        return Java.getInstance(runtime, obj);
    }

    public static RaiseException mapInstantiationException(final Ruby runtime, final Throwable e) {
        Throwable cause = e;
        while ( cause.getCause() != null ) cause = cause.getCause();
        String msg = cause.getLocalizedMessage();

        RaiseException ex = argumentError(runtime.getCurrentContext(),
                "Constructor invocation failed: " + (msg == null ? e.getClass().getName() : msg));
        ex.initCause(e);
        ex.addSuppressed(e);
        throw ex;
    }
    
    public static RuntimeException throwInstantiationExceptionCause(final Ruby runtime, final ReflectiveOperationException e) {
        Throwable cause = e;
        if (cause.getCause() != null) cause = cause.getCause();
        Helpers.throwException(cause);
        return new RuntimeException("Dead code... If you see this, file a bug: JPCtIEC fail"); // greppable
    }

    @Deprecated
    public IRubyObject new_instance(IRubyObject[] args, Block block) {
        return new_instance2(getCurrentContext(), args, block);
    }

    @JRubyMethod(required = 1, optional = 1, checkArity = false)
    public IRubyObject new_instance(ThreadContext context, IRubyObject[] args, Block block) {
        final Ruby runtime = context.runtime;

        final int last = Arity.checkArgumentCount(context, args, 1, 2) - 1;

        final RubyProc proc;
        // Is there a supplied proc arg or do we assume a block was supplied
        if (args[last] instanceof RubyProc) {
            proc = (RubyProc) args[last];
        } else {
            proc = runtime.newProc(Block.Type.PROC, block);
        }

        final Object[] convertedArgs = convertArguments((RubyArray) args[0]);

        try {
            return wrapJavaObject(runtime, newInstance(convertedArgs, runtime, proc));
        }
        catch (Exception e) {
            throw mapInstantiationException(runtime, e);
        }
    }

    //TODO: should I replace this with RubyToJavaInvoker.convertArguments calls?
    private Object[] convertArguments(final RubyArray arguments) {
        final int argsSize = arguments.size();

        final Object[] args = new Object[argsSize];
        final Class<?>[] parameterTypes = getParameterTypes();

        for ( int i = 0; i < argsSize; i++ ) {
            args[i] = arguments.eltInternal(i).toJava( parameterTypes[i] );
        }
        return args;
    }

}
