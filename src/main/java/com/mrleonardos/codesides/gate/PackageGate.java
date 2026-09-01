package com.mrleonardos.codesides.gate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Проверка границ пакетов по скомпилированным классам мода.
 *
 * <p>
 * Мод, который режется на стороны, держит типы платформы в одном пакете, а остальное оставляет чистым:
 * иначе общий код сошлётся на вырезанное и упадёт уже у игрока. Гейт обходит каталог классов и называет
 * каждую ссылку из закрытых пакетов на запрещённый префикс, включая пул констант, сигнатуры, аннотации,
 * типы в try/catch и аргументы invokedynamic.
 *
 * <p>
 * Здесь же проверка видимости слушателей событий: FML генерирует обработчик в своём пакете и обращается
 * к классу слушателя напрямую, поэтому непубличный класс с {@code @SubscribeEvent} роняет сервер
 * {@code IllegalAccessError} на первом же событии, а сборка об этом молчит.
 *
 * <p>
 * Гейт живёт здесь, а не в каждом моде, потому что это ровно та дисциплина, ради которой существует
 * разделение сторон, и правило у всех модов линейки одно.
 */
public final class PackageGate
{
	/** Префиксы платформы Minecraft: то, чего не должно быть в закрытых пакетах мода. */
	public static final String[] PLATFORM = { "net/minecraftforge", "cpw/mods/fml", "net/minecraft" };

	private final Path root;

	private final String[] forbidden;

	private PackageGate(Path root, String[] forbidden)
	{
		this.root = root;
		this.forbidden = forbidden.clone();
	}

	/**
	 * Гейт для каталога классов, в который попал указанный класс.
	 *
	 * @param anchor любой класс мода: по нему находится каталог сборки
	 * @param forbidden запрещённые префиксы, пустой список означает {@link #PLATFORM}
	 */
	public static PackageGate of(Class<?> anchor, String... forbidden) throws IOException
	{
		Path location;
		try
		{
			location = Paths.get(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
		}
		catch (Exception failure)
		{
			throw new IOException("не удалось найти каталог скомпилированных классов", failure);
		}
		if (!Files.isDirectory(location))
			throw new IOException("классы ожидаются каталогом, а вышло " + location);
		return new PackageGate(location, forbidden.length == 0 ? PLATFORM : forbidden);
	}

	/** Ссылки на запрещённые типы из перечисленных пакетов; пустой список означает порядок. */
	public List<String> violations(String... packages) throws IOException
	{
		List<String> found = new ArrayList<>();
		for (String pkg : packages)
		{
			Path dir = root.resolve(pkg.replace('.', '/'));
			if (!Files.isDirectory(dir))
				throw new IOException("пакет " + pkg + " не найден среди скомпилированных классов, прогони сборку");
			try (Stream<Path> files = Files.walk(dir))
			{
				files.filter(file -> file.toString().endsWith(".class")).sorted().forEach(file -> scan(file, found));
			}
		}
		return found;
	}

	/** Классы со слушателями событий, объявленные непублично. */
	public List<String> hiddenListeners() throws IOException
	{
		List<String> hidden = new ArrayList<>();
		try (Stream<Path> files = Files.walk(root))
		{
			files.filter(file -> file.toString().endsWith(".class")).sorted()
				.forEach(file -> checkListener(file, hidden));
		}
		return hidden;
	}

	/** Ссылки запрещённых типов в готовом байткоде: этим гейт проверяет сам себя. */
	public List<String> scan(byte[] bytes)
	{
		List<String> out = new ArrayList<>();
		ClassReader reader = new ClassReader(bytes);
		reader.accept(new ReferenceCollector(reader.getClassName(), out), 0);
		return out;
	}

	/** Класс со ссылкой на тип платформы: им проверяется, что гейт не ослеп. */
	public static byte[] foreignSample()
	{
		return foreignClass();
	}

	private void scan(Path file, List<String> out)
	{
		try
		{
			ClassReader reader = new ClassReader(Files.readAllBytes(file));
			reader.accept(new ReferenceCollector(reader.getClassName(), out), 0);
		}
		catch (IOException failure)
		{
			throw new IllegalStateException("не удалось прочитать " + file, failure);
		}
	}

	private void check(String internalName, String where, List<String> out)
	{
		if (internalName == null)
			return;
		for (String prefix : forbidden)
			if (internalName.startsWith(prefix))
			{
				out.add(where + ": " + internalName);
				return;
			}
	}

	private void checkText(String text, String where, List<String> out)
	{
		if (text == null)
			return;
		for (String prefix : forbidden)
			if (text.contains(prefix))
			{
				out.add(where + ": " + text);
				return;
			}
	}

	private static void checkListener(Path file, List<String> hidden)
	{
		try
		{
			ClassReader reader = new ClassReader(Files.readAllBytes(file));
			boolean[] subscribes = new boolean[1];
			int[] access = new int[1];
			reader.accept(new ClassVisitor(Opcodes.ASM5)
			{
				@Override
				public void visit(int version, int classAccess, String name, String signature, String superName,
					String[] interfaces)
				{
					access[0] = classAccess;
				}

				@Override
				public MethodVisitor visitMethod(int methodAccess, String name, String descriptor, String signature,
					String[] exceptions)
				{
					return new MethodVisitor(Opcodes.ASM5)
					{
						@Override
						public AnnotationVisitor visitAnnotation(String annotation, boolean visible)
						{
							if (annotation.endsWith("SubscribeEvent;"))
								subscribes[0] = true;
							return null;
						}
					};
				}
			}, ClassReader.SKIP_FRAMES);
			if (subscribes[0] && (access[0] & Opcodes.ACC_PUBLIC) == 0)
				hidden.add(reader.getClassName());
		}
		catch (IOException failure)
		{
			throw new IllegalStateException("не прочитан класс " + file, failure);
		}
	}

    private final class ReferenceCollector extends ClassVisitor {

        private final String origin;
        private final List<String> out;

        ReferenceCollector(String origin, List<String> out) {
            super(Opcodes.ASM5);
            this.origin = origin;
            this.out = out;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName,
            String[] interfaces) {
            check(superName, origin, out);
            checkText(signature, origin, out);
            if (interfaces != null) {
                for (String type : interfaces) {
                    check(type, origin, out);
                }
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            checkText(desc, origin, out);
            return super.visitAnnotation(desc, visible);
        }

        @Override
        public FieldVisitor visitField(int access, String name, String desc, String signature, Object value) {
            String where = origin + "#" + name;
            checkText(desc, where, out);
            checkText(signature, where, out);
            return new FieldVisitor(Opcodes.ASM5) {

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    checkText(desc, where, out);
                    return super.visitAnnotation(desc, visible);
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            String where = origin + "#" + name + desc;
            checkText(desc, where, out);
            checkText(signature, where, out);
            if (exceptions != null) {
                for (String type : exceptions) {
                    check(type, where, out);
                }
            }
            return new CodeCollector(where, out);
        }
    }

    private final class CodeCollector extends MethodVisitor {

        private final String where;
        private final List<String> out;

        CodeCollector(String where, List<String> out) {
            super(Opcodes.ASM5);
            this.where = where;
            this.out = out;
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
            check(owner, where, out);
            checkText(desc, where, out);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean isInterface) {
            check(owner, where, out);
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            check(type, where, out);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String desc, Handle bootstrap, Object... args) {
            checkText(desc, where, out);
            check(bootstrap.getOwner(), where, out);
            for (Object arg : args) {
                if (arg instanceof Handle) {
                    check(((Handle) arg).getOwner(), where, out);
                }
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String desc, int dimensions) {
            checkText(desc, where, out);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            check(type, where, out);
        }

        @Override
        public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index) {
            checkText(desc, where, out);
        }

        @Override
        public void visitFrame(int type, int locals, Object[] local, int stackSize, Object[] stack) {
            for (Object[] group : new Object[][] { local, stack }) {
                if (group == null) {
                    continue;
                }
                for (Object item : group) {
                    if (item instanceof String) {
                        check((String) item, where, out);
                    }
                }
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof Type) {
                check(((Type) value).getInternalName(), where, out);
            }
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            checkText(desc, where, out);
            return super.visitAnnotation(desc, visible);
        }
    }

    private static byte[] foreignClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(
            Opcodes.V1_8,
            Opcodes.ACC_PUBLIC,
            "com/mrleonardos/codeperms/internal/ForeignReference",
            null,
            "java/lang/Object",
            null);
        writer.visitField(Opcodes.ACC_PRIVATE, "player", "Lnet/minecraft/entity/player/EntityPlayer;", null, null)
            .visitEnd();
        MethodVisitor body = writer.visitMethod(Opcodes.ACC_PUBLIC, "name", "()Ljava/lang/String;", null, null);
        body.visitCode();
        body.visitVarInsn(Opcodes.ALOAD, 0);
        body.visitFieldInsn(
            Opcodes.GETFIELD,
            "com/mrleonardos/codeperms/internal/ForeignReference",
            "player",
            "Lnet/minecraft/entity/player/EntityPlayer;");
        body.visitMethodInsn(
            Opcodes.INVOKEVIRTUAL,
            "net/minecraft/entity/player/EntityPlayer",
            "getCommandSenderName",
            "()Ljava/lang/String;",
            false);
        body.visitInsn(Opcodes.ARETURN);
        body.visitMaxs(1, 1);
        body.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
