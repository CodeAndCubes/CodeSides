package com.mrleonardos.codesides.split;

import java.util.List;
import java.util.function.Consumer;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;

final class Annotations
{
	private Annotations()
	{
	}

	static void forEachType(List<? extends AnnotationNode> annotations, Consumer<Type> sink)
	{
		if (annotations == null)
			return;
		for (AnnotationNode annotation : annotations)
			forEachType(annotation, sink);
	}

	static void forEachParameterType(List<AnnotationNode>[] parameters, Consumer<Type> sink)
	{
		if (parameters == null)
			return;
		for (List<AnnotationNode> list : parameters)
			forEachType(list, sink);
	}

	static void forEachValueType(Object value, Consumer<Type> sink)
	{
		if (value instanceof Type)
			sink.accept((Type) value);
		else if (value instanceof AnnotationNode)
			forEachType((AnnotationNode) value, sink);
		else if (value instanceof String[])
			enumType((String[]) value, sink);
		else if (value instanceof List)
			for (Object item : (List<?>) value)
				forEachValueType(item, sink);
	}

	private static void forEachType(AnnotationNode annotation, Consumer<Type> sink)
	{
		if (annotation == null)
			return;
		descriptor(annotation.desc, sink);
		if (annotation.values == null)
			return;
		for (Object value : annotation.values)
			forEachValueType(value, sink);
	}

	private static void enumType(String[] constant, Consumer<Type> sink)
	{
		if (constant.length == 2)
			descriptor(constant[0], sink);
	}

	private static void descriptor(String desc, Consumer<Type> sink)
	{
		if (desc != null && !desc.isEmpty() && desc.charAt(0) != '(')
			sink.accept(Type.getType(desc));
	}
}
