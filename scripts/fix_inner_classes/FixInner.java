import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Чинит InnerClasses-атрибуты в jar, полученном из DEX (enjarify их не пишет).
 * Для каждого имени класса вида Outer$Inner добавляет корректные записи
 * InnerClasses, чтобы компиляторы (kotlinc/javac) видели вложенные классы.
 */
public class FixInner {

    static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static void main(String[] args) throws Exception {
        String inJar = args[0];
        String outJar = args[1];

        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, byte[]> otherFiles = new LinkedHashMap<>();

        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(inJar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = readAll(zin);
                if (e.getName().endsWith(".class")) {
                    classes.put(e.getName(), data);
                } else {
                    otherFiles.put(e.getName(), data);
                }
            }
        }

        // Pass 1: inner/outer relationships: binary inner name -> {outer, simple}
        Map<String, String[]> innerInfo = new HashMap<>();
        for (String path : classes.keySet()) {
            String name = path.substring(0, path.length() - ".class".length());
            int idx = name.lastIndexOf('$');
            if (idx <= 0 || idx >= name.length() - 1) continue;
            String outer = name.substring(0, idx);
            String simple = name.substring(idx + 1);
            if (!IDENT.matcher(simple).matches()) continue;
            innerInfo.put(name, new String[]{outer, simple});
        }

        // Pass 2: rewrite each class
        try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(outJar))) {
            zout.setLevel(9);
            for (Map.Entry<String, byte[]> ce : classes.entrySet()) {
                String path = ce.getKey();
                String name = path.substring(0, path.length() - ".class".length());
                byte[] fixed = fixClass(ce.getValue(), name, innerInfo);
                zout.putNextEntry(new ZipEntry(path));
                zout.write(fixed);
                zout.closeEntry();
            }
            for (Map.Entry<String, byte[]> fe : otherFiles.entrySet()) {
                zout.putNextEntry(new ZipEntry(fe.getKey()));
                zout.write(fe.getValue());
                zout.closeEntry();
            }
        }
        System.out.println("done: " + classes.size() + " classes, " + innerInfo.size() + " inner classes");
    }

    static byte[] fixClass(byte[] data, final String className, final Map<String, String[]> innerInfo) {
        ClassReader cr = new ClassReader(data);
        ClassWriter cw = new ClassWriter(cr, 0);
        final Set<String> existing = new HashSet<>();
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM7, cw) {
            @Override
            public void visitInnerClass(String name, String outerName, String innerName, int access) {
                if (outerName != null) {
                    existing.add(name);
                }
                super.visitInnerClass(name, outerName, innerName, access);
            }

            @Override
            public void visitEnd() {
                // 1) inner classes declared in this class
                for (Map.Entry<String, String[]> e : innerInfo.entrySet()) {
                    String innerName = e.getKey();
                    String outerName = e.getValue()[0];
                    String simpleName = e.getValue()[1];
                    if (outerName.equals(className) && !existing.contains(innerName)) {
                        super.visitInnerClass(innerName, className, simpleName,
                                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
                    }
                }
                // 2) this class itself if it is an inner class
                String[] self = innerInfo.get(className);
                if (self != null && !existing.contains(className)) {
                    super.visitInnerClass(className, self[0], self[1],
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
                }
                super.visitEnd();
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
