package com.raizlabs.android.dbflow.processor

import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ElementVisitor
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.Name
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.element.TypeParameterElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.ArrayType
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.ErrorType
import javax.lang.model.type.ExecutableType
import javax.lang.model.type.IntersectionType
import javax.lang.model.type.NoType
import javax.lang.model.type.NullType
import javax.lang.model.type.PrimitiveType
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.type.TypeVariable
import javax.lang.model.type.TypeVisitor
import javax.lang.model.type.UnionType
import javax.lang.model.type.WildcardType
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

/**
 * Sentinel element used during KSP processing so that parent constructors of definition classes
 * can be invoked safely. KSP subclasses / kspInit() will override all relevant fields afterward.
 */
object KSP_SENTINEL_ELEMENT : Element {
    override fun getKind() = ElementKind.CLASS
    override fun getModifiers(): MutableSet<Modifier> = mutableSetOf()
    override fun getSimpleName(): Name = EMPTY_NAME
    override fun getEnclosingElement(): Element = this
    override fun getEnclosedElements(): MutableList<Element> = mutableListOf()
    override fun <A : Annotation> getAnnotation(annotationType: Class<A>?): A? = null
    override fun getAnnotationMirrors(): MutableList<out AnnotationMirror> = mutableListOf()
    override fun asType(): TypeMirror = KSP_SENTINEL_TYPE_MIRROR
    override fun <R, P> accept(v: ElementVisitor<R, P>?, p: P): R = throw UnsupportedOperationException("KSP sentinel")
    override fun <A : Annotation> getAnnotationsByType(annotationType: Class<A>?): Array<A> =
        @Suppress("UNCHECKED_CAST") (emptyArray<Annotation>() as Array<A>)
}

/** Sentinel TypeMirror that safely represents "unknown type" in KSP mode. */
object KSP_SENTINEL_TYPE_MIRROR : TypeMirror {
    override fun getKind() = TypeKind.NONE
    override fun getAnnotationMirrors(): MutableList<AnnotationMirror> = mutableListOf()
    override fun <A : Annotation> getAnnotation(annotationType: Class<A>?): A? = null
    override fun <A : Annotation> getAnnotationsByType(annotationType: Class<A>?): Array<A> =
        @Suppress("UNCHECKED_CAST") (emptyArray<Annotation>() as Array<A>)
    override fun <R, P> accept(v: TypeVisitor<R, P>?, p: P): R = v!!.visitUnknown(this, p)
    override fun toString() = "KSP_SENTINEL"
}

private object EMPTY_NAME : Name {
    override fun contentEquals(cs: CharSequence?) = cs?.isEmpty() == true
    override fun get(index: Int): Char = throw IndexOutOfBoundsException()
    override val length: Int get() = 0
    override fun subSequence(start: Int, end: Int): CharSequence = ""
    override fun toString() = ""
}

// ---------------------------------------------------------------------------
// No-op javax implementations used by KspProcessorManager
// ---------------------------------------------------------------------------

class NoOpElements : Elements {
    override fun getPackageElement(name: CharSequence?): PackageElement? = null
    override fun getTypeElement(name: CharSequence?): TypeElement? = null
    override fun getElementValuesWithDefaults(a: javax.lang.model.element.AnnotationMirror?) = emptyMap<ExecutableElement, javax.lang.model.element.AnnotationValue>()
    override fun getDocComment(e: Element?) = null
    override fun isDeprecated(e: Element?) = false
    override fun getBinaryName(type: TypeElement?): Name = EMPTY_NAME
    override fun getPackageOf(type: Element?): PackageElement? = null
    override fun getAllMembers(type: TypeElement?): MutableList<out Element> = mutableListOf()
    override fun getAllAnnotationMirrors(e: Element?): MutableList<out AnnotationMirror> = mutableListOf()
    override fun hides(hider: Element?, hidden: Element?) = false
    override fun overrides(overrider: ExecutableElement?, overridden: ExecutableElement?, type: TypeElement?) = false
    override fun getConstantExpression(value: Any?) = value?.toString() ?: "null"
    override fun printElements(w: java.io.Writer?, vararg elements: Element?) {}
    override fun getName(cs: CharSequence?): Name = EMPTY_NAME
    override fun isFunctionalInterface(type: TypeElement?) = false
}

class NoOpTypes : Types {
    override fun asElement(t: TypeMirror?): Element? = null
    override fun isSameType(t1: TypeMirror?, t2: TypeMirror?) = false
    override fun isSubtype(t1: TypeMirror?, t2: TypeMirror?) = false
    override fun isAssignable(t1: TypeMirror?, t2: TypeMirror?) = false
    override fun contains(t1: TypeMirror?, t2: TypeMirror?) = false
    override fun isSubsignature(m1: ExecutableType?, m2: ExecutableType?) = false
    override fun directSupertypes(t: TypeMirror?): MutableList<out TypeMirror> = mutableListOf()
    override fun erasure(t: TypeMirror?): TypeMirror? = t
    override fun boxedClass(p: PrimitiveType?): TypeElement? = null
    override fun unboxedType(t: TypeMirror?): PrimitiveType? = null
    override fun capture(t: TypeMirror?): TypeMirror? = null
    override fun getPrimitiveType(kind: TypeKind?): PrimitiveType? = null
    override fun getNullType(): NullType? = null
    override fun getNoType(kind: TypeKind?): NoType? = null
    override fun getArrayType(componentType: TypeMirror?): ArrayType? = null
    override fun getWildcardType(extendsBound: TypeMirror?, superBound: TypeMirror?): WildcardType? = null
    override fun getDeclaredType(typeElem: TypeElement?, vararg typeArgs: TypeMirror?): DeclaredType? = null
    override fun getDeclaredType(containing: DeclaredType?, typeElem: TypeElement?, vararg typeArgs: TypeMirror?): DeclaredType? = null
    override fun asMemberOf(containing: DeclaredType?, element: Element?): TypeMirror? = null
}

class KspMessager(private val logger: com.google.devtools.ksp.processing.KSPLogger) : Messager {
    override fun printMessage(kind: Diagnostic.Kind?, msg: CharSequence?) {
        val message = msg?.toString() ?: return
        when (kind) {
            Diagnostic.Kind.ERROR -> logger.error(message)
            Diagnostic.Kind.WARNING, Diagnostic.Kind.MANDATORY_WARNING -> logger.warn(message)
            else -> logger.info(message)
        }
    }
    override fun printMessage(kind: Diagnostic.Kind?, msg: CharSequence?, e: Element?) = printMessage(kind, msg)
    override fun printMessage(kind: Diagnostic.Kind?, msg: CharSequence?, e: Element?, a: AnnotationMirror?) = printMessage(kind, msg)
    override fun printMessage(kind: Diagnostic.Kind?, msg: CharSequence?, e: Element?, a: AnnotationMirror?, v: javax.lang.model.element.AnnotationValue?) = printMessage(kind, msg)
}
