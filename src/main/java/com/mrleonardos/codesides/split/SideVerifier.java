package com.mrleonardos.codesides.split;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * Проверяет, что после разреза сторона осталась целой: в оставшемся коде нет ссылок на вырезанные символы
 * и не осталось абстрактных методов без реализации.
 *
 * <p>Ловится ровно то, что иначе упало бы в рантайме у игрока: {@code NoClassDefFoundError},
 * {@code NoSuchMethodError}, {@code AbstractMethodError}. Причина всегда одна: общий код зависит от того,
 * что живёт на одной стороне. Правильный приём: общий интерфейс, реализация помечена стороной.
 *
 * <p>Ссылки на чужие библиотеки ({@code java.*}, {@code net.minecraft.*}) не проверяются: вырезаются только
 * помеченные символы самого мода. Но абстрактные контракты чужих типов проверяются: контракты Minecraft,
 * Forge и библиотек по {@link ClassLookup} с их байткодом ({@code IInventory} и прочее), контракты типов
 * платформы ({@code Comparable}, {@code Supplier}, {@code Runnable}) всегда, через {@link PlatformLookup}.
 *
 * <p>Отдельно ловятся константы времени компиляции: значение {@code static final} поля javac подставляет по
 * месту использования, поэтому вырезание такого поля само по себе значение из чужого jar не убирает.
 */
public final class SideVerifier
{
	private static final String OBJECT = "java/lang/Object";

	private static final Set<String> OBJECT_METHODS = new HashSet<>(Arrays.asList("equals(Ljava/lang/Object;)Z",
		"hashCode()I", "toString()Ljava/lang/String;", "clone()Ljava/lang/Object;", "finalize()V",
		"getClass()Ljava/lang/Class;", "notify()V", "notifyAll()V", "wait()V", "wait(J)V", "wait(JI)V"));

	private SideVerifier()
	{
	}

	/**
	 * Собрать нарушения контракта разделения. Пустой список: сторона консистентна.
	 *
	 * @param out    результат разреза одной стороны
	 * @param lookup байткод внешних типов (Minecraft, Forge, библиотеки) или {@link ClassLookup#EMPTY}
	 */
	public static List<Violation> verify(SplitOutput out, ClassLookup lookup)
	{
		List<Violation> violations = new ArrayList<>();
		Map<String, ClassNode> live = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : out.classes.entrySet())
			live.put(e.getKey(), read(e.getValue()));

		for (ClassNode cn : live.values())
			checkReferences(cn, out, violations);

		checkConstants(live, out, violations);

		Resolver resolver = new Resolver(live, lookup);
		for (ClassNode cn : live.values())
			checkAbstractContract(cn, resolver, violations);

		return new ArrayList<>(new LinkedHashSet<>(violations));
	}

	private static void checkReferences(ClassNode cn, SplitOutput out, List<Violation> violations)
	{
		checkClass(cn.superName, cn.name, null, out, violations);
		if (cn.interfaces != null)
			for (String itf : cn.interfaces)
				checkClass(itf, cn.name, null, out, violations);
		checkSignature(cn.signature, cn.name, null, out, violations);
		checkAnnotations(cn.visibleAnnotations, cn.name, null, out, violations);
		checkAnnotations(cn.invisibleAnnotations, cn.name, null, out, violations);
		checkAnnotations(cn.visibleTypeAnnotations, cn.name, null, out, violations);
		checkAnnotations(cn.invisibleTypeAnnotations, cn.name, null, out, violations);

		for (FieldNode fn : cn.fields)
		{
			String where = "поле " + fn.name;
			checkDesc(fn.desc, cn.name, where, out, violations);
			checkSignature(fn.signature, cn.name, where, out, violations);
			checkAnnotations(fn.visibleAnnotations, cn.name, where, out, violations);
			checkAnnotations(fn.invisibleAnnotations, cn.name, where, out, violations);
			checkAnnotations(fn.visibleTypeAnnotations, cn.name, where, out, violations);
			checkAnnotations(fn.invisibleTypeAnnotations, cn.name, where, out, violations);
		}

		for (MethodNode mn : cn.methods)
		{
			String where = mn.name + mn.desc;
			checkDesc(mn.desc, cn.name, where, out, violations);
			checkSignature(mn.signature, cn.name, where, out, violations);
			checkAnnotations(mn.visibleAnnotations, cn.name, where, out, violations);
			checkAnnotations(mn.invisibleAnnotations, cn.name, where, out, violations);
			checkAnnotations(mn.visibleTypeAnnotations, cn.name, where, out, violations);
			checkAnnotations(mn.invisibleTypeAnnotations, cn.name, where, out, violations);
			checkAnnotations(mn.visibleLocalVariableAnnotations, cn.name, where, out, violations);
			checkAnnotations(mn.invisibleLocalVariableAnnotations, cn.name, where, out, violations);
			checkParameterAnnotations(mn.visibleParameterAnnotations, cn.name, where, out, violations);
			checkParameterAnnotations(mn.invisibleParameterAnnotations, cn.name, where, out, violations);
			checkAnnotationDefault(mn.annotationDefault, cn.name, where, out, violations);
			if (mn.exceptions != null)
				for (String ex : mn.exceptions)
					checkClass(ex, cn.name, where, out, violations);
			if (mn.tryCatchBlocks != null)
				for (TryCatchBlockNode tc : mn.tryCatchBlocks)
					checkClass(tc.type, cn.name, where, out, violations);
			if (mn.instructions == null)
				continue;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext())
				checkInstruction(insn, cn.name, mn.name, where, out, violations);
		}
	}

	private static void checkInstruction(AbstractInsnNode insn, String from, String method, String where,
		SplitOutput out, List<Violation> violations)
	{
		if (insn instanceof MethodInsnNode)
		{
			MethodInsnNode m = (MethodInsnNode) insn;
			checkMember(new MemberRef(m.owner, m.name, m.desc), out.removedMethods, Violation.KIND_METHOD, from, where,
				out, violations);
		}
		else if (insn instanceof FieldInsnNode)
		{
			FieldInsnNode f = (FieldInsnNode) insn;
			MemberRef ref = new MemberRef(f.owner, f.name, f.desc);
			if (isInitializerWrite(insn.getOpcode(), method) && from.equals(f.owner) && out.removedFields.contains(ref))
				violations.add(new Violation(from, where, ref.toString(), Violation.KIND_INITIALIZER));
			else
				checkMember(ref, out.removedFields, Violation.KIND_FIELD, from, where, out, violations);
		}
		else if (insn instanceof TypeInsnNode)
		{
			checkInternalOrDesc(((TypeInsnNode) insn).desc, from, where, out, violations);
		}
		else if (insn instanceof MultiANewArrayInsnNode)
		{
			checkDesc(((MultiANewArrayInsnNode) insn).desc, from, where, out, violations);
		}
		else if (insn instanceof LdcInsnNode)
		{
			checkConstant(((LdcInsnNode) insn).cst, from, where, out, violations);
		}
		else if (insn instanceof InvokeDynamicInsnNode)
		{
			InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
			checkDesc(indy.desc, from, where, out, violations);
			checkHandle(indy.bsm, from, where, out, violations);
			if (indy.bsmArgs != null)
				for (Object arg : indy.bsmArgs)
					checkConstant(arg, from, where, out, violations);
		}
	}

	private static void checkConstant(Object cst, String from, String where, SplitOutput out,
		List<Violation> violations)
	{
		if (cst instanceof Type)
		{
			checkType((Type) cst, from, where, out, violations);
		}
		else if (cst instanceof Handle)
		{
			checkHandle((Handle) cst, from, where, out, violations);
		}
		else if (cst instanceof ConstantDynamic)
		{
			ConstantDynamic dynamic = (ConstantDynamic) cst;
			checkDesc(dynamic.getDescriptor(), from, where, out, violations);
			checkHandle(dynamic.getBootstrapMethod(), from, where, out, violations);
			for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++)
				checkConstant(dynamic.getBootstrapMethodArgument(i), from, where, out, violations);
		}
	}

	private static void checkHandle(Handle handle, String from, String where, SplitOutput out,
		List<Violation> violations)
	{
		if (handle == null)
			return;
		MemberRef ref = new MemberRef(handle.getOwner(), handle.getName(), handle.getDesc());
		boolean field = handle.getTag() >= Opcodes.H_GETFIELD && handle.getTag() <= Opcodes.H_PUTSTATIC;
		checkMember(ref, field ? out.removedFields : out.removedMethods,
			field ? Violation.KIND_FIELD : Violation.KIND_METHOD, from, where, out, violations);
	}

	private static void checkMember(MemberRef ref, Set<MemberRef> removed, String kind, String from, String where,
		SplitOutput out, List<Violation> violations)
	{
		if (out.removedClasses.contains(ref.owner))
		{
			violations.add(new Violation(from, where, ref.owner, Violation.KIND_CLASS));
			return;
		}
		if (removed.contains(ref))
			violations.add(new Violation(from, where, ref.toString(), kind));
	}

	private static boolean isInitializerWrite(int opcode, String method)
	{
		return (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC)
			&& ("<init>".equals(method) || "<clinit>".equals(method));
	}

	private static void checkAnnotations(List<? extends AnnotationNode> annotations, String from, String where,
		SplitOutput out, List<Violation> violations)
	{
		Annotations.forEachType(annotations, t -> checkType(t, from, where, out, violations));
	}

	private static void checkParameterAnnotations(List<AnnotationNode>[] parameters, String from, String where,
		SplitOutput out, List<Violation> violations)
	{
		Annotations.forEachParameterType(parameters, t -> checkType(t, from, where, out, violations));
	}

	private static void checkAnnotationDefault(Object value, String from, String where, SplitOutput out,
		List<Violation> violations)
	{
		Annotations.forEachValueType(value, t -> checkType(t, from, where, out, violations));
	}

	private static void checkConstants(Map<String, ClassNode> live, SplitOutput out, List<Violation> violations)
	{
		if (out.removedConstants.isEmpty())
			return;
		for (Map.Entry<MemberRef, Object> constant : out.removedConstants.entrySet())
			if (out.removedFields.contains(constant.getKey()))
				violations.add(new Violation(constant.getKey().owner, "поле " + constant.getKey().name,
					constant.getKey().toString(), Violation.KIND_CONSTANT));

		Set<Object> wanted = new HashSet<>(out.removedConstants.values());
		for (ClassNode cn : live.values())
		{
			Set<Object> present = constantsOf(cn, wanted);
			if (present.isEmpty())
				continue;
			for (Map.Entry<MemberRef, Object> constant : out.removedConstants.entrySet())
				if (present.contains(constant.getValue()))
					violations.add(new Violation(cn.name, null, constant.getKey().toString(), Violation.KIND_INLINED));
		}
	}

	private static Set<Object> constantsOf(ClassNode cn, Set<Object> wanted)
	{
		Set<Object> present = new HashSet<>();
		for (FieldNode fn : cn.fields)
			if (fn.value != null && wanted.contains(fn.value))
				present.add(fn.value);
		for (MethodNode mn : cn.methods)
		{
			if (mn.instructions == null)
				continue;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext())
			{
				if (!(insn instanceof LdcInsnNode))
					continue;
				Object cst = ((LdcInsnNode) insn).cst;
				if (wanted.contains(cst))
					present.add(cst);
			}
		}
		return present;
	}

	private static void checkClass(String internalName, String from, String where, SplitOutput out,
		List<Violation> violations)
	{
		if (internalName != null && out.removedClasses.contains(internalName))
			violations.add(new Violation(from, where, internalName, Violation.KIND_CLASS));
	}

	private static void checkInternalOrDesc(String value, String from, String where, SplitOutput out,
		List<Violation> violations)
	{
		if (value == null || value.isEmpty())
			return;
		if (value.charAt(0) == '[' || value.charAt(0) == 'L')
			checkDesc(value, from, where, out, violations);
		else
			checkClass(value, from, where, out, violations);
	}

	private static void checkDesc(String desc, String from, String where, SplitOutput out, List<Violation> violations)
	{
		if (desc == null || desc.isEmpty())
			return;
		if (desc.charAt(0) == '(')
		{
			for (Type t : Type.getArgumentTypes(desc))
				checkType(t, from, where, out, violations);
			checkType(Type.getReturnType(desc), from, where, out, violations);
		}
		else
		{
			checkType(Type.getType(desc), from, where, out, violations);
		}
	}

	private static void checkType(Type type, String from, String where, SplitOutput out, List<Violation> violations)
	{
		if (type == null)
			return;
		Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
		if (element.getSort() == Type.OBJECT)
			checkClass(element.getInternalName(), from, where, out, violations);
	}

	private static void checkSignature(String signature, String from, String where, SplitOutput out,
		List<Violation> violations)
	{
		if (signature == null)
			return;
		int i = 0;
		while ((i = signature.indexOf('L', i)) >= 0)
		{
			int end = i + 1;
			while (end < signature.length() && ";<.".indexOf(signature.charAt(end)) < 0)
				end++;
			String name = signature.substring(i + 1, end);
			if (out.removedClasses.contains(name))
				violations.add(new Violation(from, where, name, Violation.KIND_SIGNATURE));
			i = end;
		}
	}

	private static void checkAbstractContract(ClassNode cn, Resolver resolver, List<Violation> violations)
	{
		if ((cn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE)) != 0)
			return;
		Map<String, String> required = new LinkedHashMap<>();
		collectAbstractMethods(cn, resolver, required, new HashSet<String>());
		for (Map.Entry<String, String> e : required.entrySet())
			if (!hasImplementation(cn, e.getKey(), resolver, new HashSet<String>()))
				violations.add(new Violation(cn.name, null, e.getKey() + " (объявлен в " + e.getValue().replace('/', '.')
					+ ")", Violation.KIND_ABSTRACT));
	}

	private static void collectAbstractMethods(ClassNode cn, Resolver resolver, Map<String, String> required,
		Set<String> visited)
	{
		for (String parent : supertypes(cn))
		{
			if (!visited.add(parent))
				continue;
			ClassNode node = resolver.resolve(parent);
			if (node == null)
				continue;
			for (MethodNode mn : node.methods)
			{
				if ((mn.access & Opcodes.ACC_ABSTRACT) == 0
					|| (mn.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0)
					continue;
				String key = mn.name + mn.desc;
				if (!required.containsKey(key))
					required.put(key, node.name);
			}
			collectAbstractMethods(node, resolver, required, visited);
		}
	}

	private static boolean hasImplementation(ClassNode cn, String key, Resolver resolver, Set<String> visited)
	{
		if (OBJECT_METHODS.contains(key))
			return true;
		if (!visited.add(cn.name))
			return false;
		for (MethodNode mn : cn.methods)
			if ((mn.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0
				&& key.equals(mn.name + mn.desc))
				return true;
		for (String parent : supertypes(cn))
		{
			ClassNode node = resolver.resolve(parent);
			if (node == null)
			{
				if (!OBJECT.equals(parent))
					return true;
				continue;
			}
			if (hasImplementation(node, key, resolver, visited))
				return true;
		}
		return false;
	}

	private static List<String> supertypes(ClassNode cn)
	{
		List<String> parents = new ArrayList<>();
		if (cn.superName != null)
			parents.add(cn.superName);
		if (cn.interfaces != null)
			parents.addAll(cn.interfaces);
		return parents;
	}

	private static ClassNode read(byte[] bytes)
	{
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, ClassReader.SKIP_FRAMES);
		return cn;
	}

	private static final class Resolver
	{
		private final Map<String, ClassNode> live;
		private final ClassLookup lookup;
		private final Map<String, ClassNode> external = new HashMap<>();

		Resolver(Map<String, ClassNode> live, ClassLookup lookup)
		{
			this.live = live;
			this.lookup = lookup;
		}

		ClassNode resolve(String internalName)
		{
			ClassNode node = live.get(internalName);
			if (node != null)
				return node;
			if (external.containsKey(internalName))
				return external.get(internalName);
			byte[] bytes = lookup.find(internalName);
			if (bytes == null)
				bytes = PlatformLookup.INSTANCE.find(internalName);
			ClassNode resolved = bytes == null ? null : read(bytes);
			external.put(internalName, resolved);
			return resolved;
		}
	}
}
