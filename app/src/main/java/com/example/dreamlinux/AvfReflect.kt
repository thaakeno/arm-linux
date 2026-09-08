package com.example.dreamlinux

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

internal object AvfReflect {
    fun unwrap(t: Throwable): Throwable {
        var x = t
        while (x is InvocationTargetException && x.targetException != null) x = x.targetException
        return x
    }

    private fun compatible(parameter: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !parameter.isPrimitive
        if (!parameter.isPrimitive) return parameter.isAssignableFrom(arg.javaClass)
        return when (parameter) {
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Long.TYPE -> arg is Long
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Float.TYPE -> arg is Float
            java.lang.Double.TYPE -> arg is Double
            else -> true
        }
    }

    private fun findMethod(type: Class<*>, name: String, args: Array<out Any?>): Method? =
        type.methods.firstOrNull { method ->
            method.name == name && method.parameterCount == args.size &&
                method.parameterTypes.indices.all { i -> compatible(method.parameterTypes[i], args[i]) }
        }

    fun call(target: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(target.javaClass, name, args)
            ?: error("Method ${target.javaClass.name}.$name/${args.size} not found")
        return try { method.invoke(target, *args) } catch (t: Throwable) { throw unwrap(t) }
    }

    fun callOptional(target: Any, name: String, vararg args: Any?): Any? {
        val method = findMethod(target.javaClass, name, args) ?: return null
        return try { method.invoke(target, *args) } catch (t: Throwable) { throw unwrap(t) }
    }

    fun staticCall(type: Class<*>, name: String, vararg args: Any?): Any? {
        val method = findMethod(type, name, args)
            ?: error("Static method ${type.name}.$name/${args.size} not found")
        return try { method.invoke(null, *args) } catch (t: Throwable) { throw unwrap(t) }
    }
}
