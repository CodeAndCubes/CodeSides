package com.mrleonardos.codesides.split;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;

import com.mrleonardos.codesides.Side;

/**
 * Ядро CodeSides: разрезает набор скомпилированных классов под одну сторону, физически удаляя код
 * противоположной.
 *
 * <p>Пометки, по убыванию охвата:
 * <ol>
 *   <li>пакет: аннотация на {@code package-info} распространяется на пакет и все его подпакеты;</li>
 *   <li>класс: аннотация на классе вырезает его вместе с вложенными классами;</li>
 *   <li>член: аннотация на методе, конструкторе или поле вырезает этот член.</li>
 * </ol>
 * Более глубокая пометка побеждает более широкую. Понимается и FML {@code @SideOnly}, так что уже
 * размеченный код режется без переделки. Непомеченное считается общим и попадает в обе стороны.
 *
 * <p>За помеченным кодом следом уходит всё, что компилятор из него сгенерировал: лямбды
 * ({@code lambda$...}), анонимные и локальные классы, методы доступа ({@code access$...}). Они не несут
 * аннотаций, поэтому вычисляются достижимостью: сгенерированный член, до которого не дотягивается ни одна
 * ссылка из оставшегося кода, удаляется. Без этого тело серверной лямбды уехало бы в клиентский jar.
 * Тела serializable-лямбд вырезанного метода уходят по имени: единственная оставшаяся ссылка на них живёт
 * внутри {@code $deserializeLambda$}, который всегда остаётся, и достижимость их не вычищает.
 *
 * <p>Ссылки в оставшемся коде не переписываются: реестр вырезанного отдаётся {@link SideVerifier},
 * который ловит битые ссылки на сборке, а не в рантайме у игрока.
 */
public final class SideSplitter
{
	private static final String DESC_SERVER = "Lcom/mrleonardos/codesides/ServerSide;";
	private static final String DESC_CLIENT = "Lcom/mrleonardos/codesides/ClientSide;";
	private static final String DESC_SIDEONLY_LEGACY = "Lcpw/mods/fml/relauncher/SideOnly;";
	private static final String DESC_SIDEONLY_MODERN = "Lnet/minecraftforge/fml/relauncher/SideOnly;";
	private static final String DESC_FML_SIDE_LEGACY = "Lcpw/mods/fml/relauncher/Side;";
	private static final String DESC_FML_SIDE_MODERN = "Lnet/minecraftforge/fml/relauncher/Side;";
	static final String DESERIALIZE_LAMBDA = "$deserializeLambda$";
	static final String LAMBDA_PREFIX = "lambda$";

	private SideSplitter()
	{
	}

	/**
	 * Разрезать набор классов под сторону {@code keep}.
	 *
	 * @param input internal name → байткод: все скомпилированные классы мода
	 * @param keep  сторона, которую оставляем
	 */
	public static SplitOutput split(Map<String, byte[]> input, Side keep)
	{
		final Side other = keep.opposite();
		final Map<String, ClassNode> nodes = new LinkedHashMap<>();
		final Map<String, Side> packageSide = new HashMap<>();
		final Map<String, Side> classSide = new HashMap<>();

		for (Map.Entry<String, byte[]> e : input.entrySet())
		{
			ClassNode cn = read(e.getValue());
			nodes.put(cn.name, cn);
			Side s = sideOf(cn.visibleAnnotations, cn.invisibleAnnotations);
			if (isPackageInfo(cn.name))
			{
				if (s != null)
					packageSide.put(packageOf(cn.name), s);
			}
			else if (s != null)
			{
				classSide.put(cn.name, s);
			}
		}

		final Set<String> removedClasses = new LinkedHashSet<>();
		final Set<MemberRef> removedMethods = new LinkedHashSet<>();
		final Set<MemberRef> removedFields = new LinkedHashSet<>();

		for (ClassNode cn : nodes.values())
			if (!isPackageInfo(cn.name) && effectiveClassSide(cn.name, classSide, packageSide) == other)
				removedClasses.add(cn.name);

		for (ClassNode cn : nodes.values())
		{
			if (isPackageInfo(cn.name) || removedClasses.contains(cn.name))
				continue;
			for (MethodNode mn : cn.methods)
				if (sideOf(mn.visibleAnnotations, mn.invisibleAnnotations) == other)
					removedMethods.add(new MemberRef(cn.name, mn.name, mn.desc));
			for (FieldNode fn : cn.fields)
				if (sideOf(fn.visibleAnnotations, fn.invisibleAnnotations) == other)
					removedFields.add(new MemberRef(cn.name, fn.name, fn.desc));
		}

		cascadeEnclosing(nodes, removedClasses, removedMethods);
		cascadeLambdaBodies(nodes, removedClasses, removedMethods, removedFields);
		sweepGenerated(nodes, removedClasses, removedMethods, removedFields);

		final Map<MemberRef, Object> removedConstants = collectConstants(nodes, removedClasses, removedFields);

		final Map<String, byte[]> out = new LinkedHashMap<>();
		for (ClassNode cn : nodes.values())
		{
			if (removedClasses.contains(cn.name))
				continue;
			if (isPackageInfo(cn.name) && packageSideRecursive(packageOf(cn.name), packageSide) == other)
				continue;
			out.put(cn.name, rewrite(cn, removedClasses, removedMethods, removedFields));
		}

		return new SplitOutput(keep, out, removedClasses, removedMethods, removedFields, removedConstants);
	}

	private static Map<MemberRef, Object> collectConstants(Map<String, ClassNode> nodes, Set<String> removedClasses,
		Set<MemberRef> removedFields)
	{
		final Set<String> keptOuters = new HashSet<>();
		for (ClassNode cn : nodes.values())
			if (!removedClasses.contains(cn.name) && !isPackageInfo(cn.name))
				keptOuters.add(topLevelOf(cn.name));

		final Map<MemberRef, Object> constants = new LinkedHashMap<>();
		for (ClassNode cn : nodes.values())
		{
			if (isPackageInfo(cn.name))
				continue;
			boolean whole = removedClasses.contains(cn.name);
			boolean readable = keptOuters.contains(topLevelOf(cn.name));
			for (FieldNode fn : cn.fields)
			{
				if (fn.value == null)
					continue;
				MemberRef ref = new MemberRef(cn.name, fn.name, fn.desc);
				if (!whole && !removedFields.contains(ref))
					continue;
				if ((fn.access & Opcodes.ACC_PRIVATE) != 0 && !readable)
					continue;
				constants.put(ref, fn.value);
			}
		}
		return constants;
	}

	private static String topLevelOf(String internalName)
	{
		int dollar = internalName.indexOf('$', internalName.lastIndexOf('/') + 1);
		return dollar < 0 ? internalName : internalName.substring(0, dollar);
	}

	private static Side effectiveClassSide(String name, Map<String, Side> classSide, Map<String, Side> packageSide)
	{
		Side s = classSide.get(name);
		if (s != null)
			return s;
		String outer = name;
		while (true)
		{
			int dollar = outer.lastIndexOf('$');
			if (dollar <= outer.lastIndexOf('/'))
				break;
			outer = outer.substring(0, dollar);
			s = classSide.get(outer);
			if (s != null)
				return s;
		}
		return packageSideRecursive(packageOf(name), packageSide);
	}

	private static Side packageSideRecursive(String pkg, Map<String, Side> packageSide)
	{
		String current = pkg;
		while (current != null)
		{
			Side s = packageSide.get(current);
			if (s != null)
				return s;
			int slash = current.lastIndexOf('/');
			current = slash < 0 ? null : current.substring(0, slash);
		}
		return null;
	}

	private static void cascadeEnclosing(Map<String, ClassNode> nodes, Set<String> removedClasses,
		Set<MemberRef> removedMethods)
	{
		boolean changed = true;
		while (changed)
		{
			changed = false;
			for (ClassNode cn : nodes.values())
			{
				if (removedClasses.contains(cn.name))
					continue;
				boolean dead = cn.outerClass != null && removedClasses.contains(cn.outerClass);
				if (!dead && cn.outerMethod != null)
					dead = removedMethods.contains(new MemberRef(cn.outerClass, cn.outerMethod, cn.outerMethodDesc));
				if (!dead)
					dead = enclosedByRemoved(cn, removedClasses);
				if (dead)
				{
					removedClasses.add(cn.name);
					changed = true;
				}
			}
		}
	}

	private static boolean enclosedByRemoved(ClassNode cn, Set<String> removedClasses)
	{
		if (cn.innerClasses == null)
			return false;
		for (InnerClassNode inner : cn.innerClasses)
			if (cn.name.equals(inner.name) && inner.outerName != null && removedClasses.contains(inner.outerName))
				return true;
		return false;
	}

	private static void cascadeLambdaBodies(Map<String, ClassNode> nodes, Set<String> removedClasses,
		Set<MemberRef> removedMethods, Set<MemberRef> removedFields)
	{
		for (ClassNode cn : nodes.values())
		{
			if (removedClasses.contains(cn.name) || isPackageInfo(cn.name))
				continue;
			Set<String> dead = new HashSet<>();
			Set<String> live = new HashSet<>();
			for (MethodNode mn : cn.methods)
			{
				if (removedMethods.contains(new MemberRef(cn.name, mn.name, mn.desc)))
					dead.add(lambdaOwnerName(mn.name));
				else
					live.add(lambdaOwnerName(mn.name));
			}
			for (FieldNode fn : cn.fields)
				if (!removedFields.contains(new MemberRef(cn.name, fn.name, fn.desc)))
					live.add(lambdaOwnerName(fn.name));
			dead.removeAll(live);
			if (dead.isEmpty())
				continue;
			for (MethodNode mn : cn.methods)
			{
				if (!mn.name.startsWith(LAMBDA_PREFIX)
					|| removedMethods.contains(new MemberRef(cn.name, mn.name, mn.desc)))
					continue;
				String owner = mn.name.substring(LAMBDA_PREFIX.length());
				for (String name : dead)
					if (owner.equals(name) || owner.startsWith(name + "$"))
					{
						removedMethods.add(new MemberRef(cn.name, mn.name, mn.desc));
						break;
					}
			}
		}
	}

	private static String lambdaOwnerName(String memberName)
	{
		return "<init>".equals(memberName) ? "new" : memberName;
	}

	private static void sweepGenerated(Map<String, ClassNode> nodes, Set<String> removedClasses,
		Set<MemberRef> removedMethods, Set<MemberRef> removedFields)
	{
		final Set<String> deadClasses = new LinkedHashSet<>();
		final Set<MemberRef> deadMethods = new LinkedHashSet<>();
		final Set<MemberRef> deadFields = new LinkedHashSet<>();

		for (ClassNode cn : nodes.values())
		{
			if (removedClasses.contains(cn.name) || isPackageInfo(cn.name))
				continue;
			if (isGeneratedClass(cn))
				deadClasses.add(cn.name);
			for (MethodNode mn : cn.methods)
			{
				MemberRef ref = new MemberRef(cn.name, mn.name, mn.desc);
				if (!removedMethods.contains(ref) && isGeneratedMember(mn.access, mn.name))
					deadMethods.add(ref);
			}
			for (FieldNode fn : cn.fields)
			{
				MemberRef ref = new MemberRef(cn.name, fn.name, fn.desc);
				if (!removedFields.contains(ref) && isGeneratedMember(fn.access, fn.name))
					deadFields.add(ref);
			}
		}

		boolean changed = true;
		while (changed && !(deadClasses.isEmpty() && deadMethods.isEmpty() && deadFields.isEmpty()))
		{
			Set<String> types = new HashSet<>();
			Set<MemberRef> members = new HashSet<>();
			for (ClassNode cn : nodes.values())
			{
				if (removedClasses.contains(cn.name) || deadClasses.contains(cn.name))
					continue;
				collectClassReferences(cn, removedMethods, removedFields, deadMethods, deadFields, types, members);
			}
			changed = revive(deadClasses, types) | revive(deadMethods, members) | revive(deadFields, members);
		}

		removedClasses.addAll(deadClasses);
		removedMethods.addAll(deadMethods);
		removedFields.addAll(deadFields);
	}

	private static <T> boolean revive(Set<T> dead, Set<T> referenced)
	{
		boolean changed = false;
		for (Iterator<T> it = dead.iterator(); it.hasNext();)
		{
			if (referenced.contains(it.next()))
			{
				it.remove();
				changed = true;
			}
		}
		return changed;
	}

	private static void collectClassReferences(ClassNode cn, Set<MemberRef> removedMethods,
		Set<MemberRef> removedFields, Set<MemberRef> deadMethods, Set<MemberRef> deadFields, Set<String> types,
		Set<MemberRef> members)
	{
		addType(cn.superName, types);
		if (cn.interfaces != null)
			for (String itf : cn.interfaces)
				addType(itf, types);
		addAnnotations(cn.visibleAnnotations, types);
		addAnnotations(cn.invisibleAnnotations, types);
		addAnnotations(cn.visibleTypeAnnotations, types);
		addAnnotations(cn.invisibleTypeAnnotations, types);

		for (FieldNode fn : cn.fields)
		{
			MemberRef ref = new MemberRef(cn.name, fn.name, fn.desc);
			if (removedFields.contains(ref) || deadFields.contains(ref))
				continue;
			addDesc(fn.desc, types);
			addAnnotations(fn.visibleAnnotations, types);
			addAnnotations(fn.invisibleAnnotations, types);
			addAnnotations(fn.visibleTypeAnnotations, types);
			addAnnotations(fn.invisibleTypeAnnotations, types);
		}

		for (MethodNode mn : cn.methods)
		{
			MemberRef ref = new MemberRef(cn.name, mn.name, mn.desc);
			if (removedMethods.contains(ref) || deadMethods.contains(ref))
				continue;
			addDesc(mn.desc, types);
			addAnnotations(mn.visibleAnnotations, types);
			addAnnotations(mn.invisibleAnnotations, types);
			addAnnotations(mn.visibleTypeAnnotations, types);
			addAnnotations(mn.invisibleTypeAnnotations, types);
			addAnnotations(mn.visibleLocalVariableAnnotations, types);
			addAnnotations(mn.invisibleLocalVariableAnnotations, types);
			Annotations.forEachParameterType(mn.visibleParameterAnnotations, t -> addType(t, types));
			Annotations.forEachParameterType(mn.invisibleParameterAnnotations, t -> addType(t, types));
			Annotations.forEachValueType(mn.annotationDefault, t -> addType(t, types));
			if (mn.exceptions != null)
				for (String ex : mn.exceptions)
					addType(ex, types);
			if (mn.tryCatchBlocks != null)
				for (TryCatchBlockNode tc : mn.tryCatchBlocks)
					addType(tc.type, types);
			if (mn.instructions == null)
				continue;
			for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext())
				collectInsnReferences(insn, types, members);
		}
	}

	private static void collectInsnReferences(AbstractInsnNode insn, Set<String> types, Set<MemberRef> members)
	{
		if (insn instanceof MethodInsnNode)
		{
			MethodInsnNode m = (MethodInsnNode) insn;
			addType(m.owner, types);
			addDesc(m.desc, types);
			members.add(new MemberRef(m.owner, m.name, m.desc));
		}
		else if (insn instanceof FieldInsnNode)
		{
			FieldInsnNode f = (FieldInsnNode) insn;
			addType(f.owner, types);
			addDesc(f.desc, types);
			members.add(new MemberRef(f.owner, f.name, f.desc));
		}
		else if (insn instanceof TypeInsnNode)
		{
			addInternalOrDesc(((TypeInsnNode) insn).desc, types);
		}
		else if (insn instanceof MultiANewArrayInsnNode)
		{
			addDesc(((MultiANewArrayInsnNode) insn).desc, types);
		}
		else if (insn instanceof LdcInsnNode)
		{
			addConstant(((LdcInsnNode) insn).cst, types, members);
		}
		else if (insn instanceof InvokeDynamicInsnNode)
		{
			InvokeDynamicInsnNode indy = (InvokeDynamicInsnNode) insn;
			addDesc(indy.desc, types);
			addHandle(indy.bsm, types, members);
			if (indy.bsmArgs != null)
				for (Object arg : indy.bsmArgs)
					addConstant(arg, types, members);
		}
		else if (insn instanceof FrameNode)
		{
			FrameNode frame = (FrameNode) insn;
			addFrameTypes(frame.local, types);
			addFrameTypes(frame.stack, types);
		}
	}

	private static void addFrameTypes(List<Object> entries, Set<String> types)
	{
		if (entries == null)
			return;
		for (Object entry : entries)
			if (entry instanceof String)
				addInternalOrDesc((String) entry, types);
	}

	private static void addConstant(Object cst, Set<String> types, Set<MemberRef> members)
	{
		if (cst instanceof Type)
		{
			addType((Type) cst, types);
		}
		else if (cst instanceof Handle)
		{
			addHandle((Handle) cst, types, members);
		}
		else if (cst instanceof ConstantDynamic)
		{
			ConstantDynamic dynamic = (ConstantDynamic) cst;
			addDesc(dynamic.getDescriptor(), types);
			addHandle(dynamic.getBootstrapMethod(), types, members);
			for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++)
				addConstant(dynamic.getBootstrapMethodArgument(i), types, members);
		}
	}

	private static void addHandle(Handle handle, Set<String> types, Set<MemberRef> members)
	{
		if (handle == null)
			return;
		addType(handle.getOwner(), types);
		addDesc(handle.getDesc(), types);
		members.add(new MemberRef(handle.getOwner(), handle.getName(), handle.getDesc()));
	}

	private static void addAnnotations(List<? extends AnnotationNode> annotations, Set<String> types)
	{
		Annotations.forEachType(annotations, t -> addType(t, types));
	}

	private static void addDesc(String desc, Set<String> types)
	{
		if (desc == null || desc.isEmpty())
			return;
		if (desc.charAt(0) == '(')
		{
			for (Type t : Type.getArgumentTypes(desc))
				addType(t, types);
			addType(Type.getReturnType(desc), types);
		}
		else
		{
			addType(Type.getType(desc), types);
		}
	}

	private static void addInternalOrDesc(String value, Set<String> types)
	{
		if (value == null || value.isEmpty())
			return;
		if (value.charAt(0) == '[' || value.charAt(0) == 'L')
			addDesc(value, types);
		else
			addType(value, types);
	}

	private static void addType(Type type, Set<String> types)
	{
		if (type == null)
			return;
		Type element = type.getSort() == Type.ARRAY ? type.getElementType() : type;
		if (element.getSort() == Type.OBJECT)
			types.add(element.getInternalName());
	}

	private static void addType(String internalName, Set<String> types)
	{
		if (internalName != null)
			types.add(internalName);
	}

	private static boolean isGeneratedClass(ClassNode cn)
	{
		return cn.outerClass != null || (cn.access & Opcodes.ACC_SYNTHETIC) != 0;
	}

	private static boolean isGeneratedMember(int access, String name)
	{
		return (access & Opcodes.ACC_SYNTHETIC) != 0 && (access & Opcodes.ACC_BRIDGE) == 0
			&& !"<init>".equals(name) && !"<clinit>".equals(name) && !DESERIALIZE_LAMBDA.equals(name);
	}

	private static byte[] rewrite(ClassNode cn, Set<String> removedClasses, Set<MemberRef> removedMethods,
		Set<MemberRef> removedFields)
	{
		for (Iterator<FieldNode> it = cn.fields.iterator(); it.hasNext();)
		{
			FieldNode fn = it.next();
			if (removedFields.contains(new MemberRef(cn.name, fn.name, fn.desc)))
				it.remove();
			else
				stripSideAnnotations(fn.visibleAnnotations, fn.invisibleAnnotations, fn.visibleTypeAnnotations,
					fn.invisibleTypeAnnotations);
		}
		for (Iterator<MethodNode> it = cn.methods.iterator(); it.hasNext();)
		{
			MethodNode mn = it.next();
			if (removedMethods.contains(new MemberRef(cn.name, mn.name, mn.desc)))
			{
				it.remove();
				continue;
			}
			stripSideAnnotations(mn.visibleAnnotations, mn.invisibleAnnotations, mn.visibleTypeAnnotations,
				mn.invisibleTypeAnnotations);
			stripSideAnnotations(mn.visibleLocalVariableAnnotations, mn.invisibleLocalVariableAnnotations, null, null);
			stripParameterAnnotations(mn.visibleParameterAnnotations);
			stripParameterAnnotations(mn.invisibleParameterAnnotations);
			stripDebugInfo(mn, removedClasses);
		}
		stripSideAnnotations(cn.visibleAnnotations, cn.invisibleAnnotations, cn.visibleTypeAnnotations,
			cn.invisibleTypeAnnotations);
		cleanNesting(cn, removedClasses);

		ClassWriter cw = new ClassWriter(0);
		cn.accept(cw);
		return cw.toByteArray();
	}

	private static void stripDebugInfo(MethodNode mn, Set<String> removedClasses)
	{
		if (mn.localVariables == null)
			return;
		for (Iterator<LocalVariableNode> it = mn.localVariables.iterator(); it.hasNext();)
		{
			LocalVariableNode local = it.next();
			if (referencesRemoved(local.desc, removedClasses))
				it.remove();
			else if (mentionsRemoved(local.signature, removedClasses))
				local.signature = null;
		}
	}

	private static boolean mentionsRemoved(String signature, Set<String> removedClasses)
	{
		for (String name : Signatures.classNames(signature))
			if (removedClasses.contains(name))
				return true;
		return false;
	}

	private static boolean referencesRemoved(String desc, Set<String> removedClasses)
	{
		if (desc == null || desc.isEmpty() || desc.charAt(0) == '(')
			return false;
		Type type = Type.getType(desc);
		if (type.getSort() == Type.ARRAY)
			type = type.getElementType();
		return type.getSort() == Type.OBJECT && removedClasses.contains(type.getInternalName());
	}

	private static void cleanNesting(ClassNode cn, Set<String> removedClasses)
	{
		if (cn.innerClasses != null)
		{
			for (Iterator<InnerClassNode> it = cn.innerClasses.iterator(); it.hasNext();)
			{
				InnerClassNode inner = it.next();
				if (removedClasses.contains(inner.name)
					|| (inner.outerName != null && removedClasses.contains(inner.outerName)))
					it.remove();
			}
		}
		if (cn.outerClass != null && removedClasses.contains(cn.outerClass))
		{
			cn.outerClass = null;
			cn.outerMethod = null;
			cn.outerMethodDesc = null;
		}
		if (cn.nestHostClass != null && removedClasses.contains(cn.nestHostClass))
			cn.nestHostClass = null;
		if (cn.nestMembers != null)
			cn.nestMembers.removeAll(removedClasses);
		if (cn.permittedSubclasses != null)
			cn.permittedSubclasses.removeAll(removedClasses);
	}

	private static void stripParameterAnnotations(List<AnnotationNode>[] parameters)
	{
		if (parameters == null)
			return;
		for (List<AnnotationNode> list : parameters)
			stripSideAnnotations(list, null, null, null);
	}

	@SafeVarargs
	private static void stripSideAnnotations(List<? extends AnnotationNode>... lists)
	{
		for (List<? extends AnnotationNode> list : lists)
		{
			if (list == null)
				continue;
			for (Iterator<? extends AnnotationNode> it = list.iterator(); it.hasNext();)
			{
				String desc = it.next().desc;
				if (DESC_SERVER.equals(desc) || DESC_CLIENT.equals(desc))
					it.remove();
			}
		}
	}

	private static Side sideOf(List<AnnotationNode> visible, List<AnnotationNode> invisible)
	{
		Side s = scan(visible);
		return s != null ? s : scan(invisible);
	}

	private static Side scan(List<AnnotationNode> annotations)
	{
		if (annotations == null)
			return null;
		for (AnnotationNode a : annotations)
		{
			if (DESC_SERVER.equals(a.desc))
				return Side.SERVER;
			if (DESC_CLIENT.equals(a.desc))
				return Side.CLIENT;
			if ((DESC_SIDEONLY_LEGACY.equals(a.desc) || DESC_SIDEONLY_MODERN.equals(a.desc)) && a.values != null)
			{
				Side fml = fmlSide(a.values);
				if (fml != null)
					return fml;
			}
		}
		return null;
	}

	private static Side fmlSide(List<Object> values)
	{
		for (int i = 0; i + 1 < values.size(); i += 2)
		{
			if (!"value".equals(values.get(i)))
				continue;
			Object v = values.get(i + 1);
			if (!(v instanceof String[]))
				continue;
			String[] enumValue = (String[]) v;
			if (DESC_FML_SIDE_LEGACY.equals(enumValue[0]) || DESC_FML_SIDE_MODERN.equals(enumValue[0]))
				return "CLIENT".equals(enumValue[1]) ? Side.CLIENT : Side.SERVER;
		}
		return null;
	}

	private static ClassNode read(byte[] bytes)
	{
		ClassNode cn = new ClassNode();
		new ClassReader(bytes).accept(cn, 0);
		return cn;
	}

	private static boolean isPackageInfo(String internalName)
	{
		return internalName.endsWith("/package-info") || internalName.equals("package-info");
	}

	private static String packageOf(String internalName)
	{
		int slash = internalName.lastIndexOf('/');
		return slash < 0 ? "" : internalName.substring(0, slash);
	}
}
