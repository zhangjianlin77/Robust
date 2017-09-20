package robust.gradle.plugin.asm;

import com.android.utils.AsmUtils;
import com.meituan.robust.ChangeQuickRedirect;
import com.meituan.robust.Constants;
import com.meituan.robust.RobustMethodId;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipOutputStream;

import javassist.CannotCompileException;
import javassist.CtClass;
import javassist.CtField;
import javassist.bytecode.AccessFlag;
import robust.gradle.plugin.AnonymousUtils;
import robust.gradle.plugin.InsertcodeStrategy;


/**
 * Created by zhangmeng on 2017/5/10.
 */

public class AsmInsertImpl extends InsertcodeStrategy {


    public AsmInsertImpl(List<String> hotfixPackageList, List<String> hotfixMethodList, List<String> exceptPackageList, List<String> exceptMethodList, boolean isHotfixMethodLevel, boolean isExceptMethodLevel) {
        super(hotfixPackageList, hotfixMethodList, exceptPackageList, exceptMethodList, isHotfixMethodLevel, isExceptMethodLevel);
    }

    @Override
    protected void insertCode(List<CtClass> box, File jarFile) throws IOException, CannotCompileException {
        ZipOutputStream outStream = new JarOutputStream(new FileOutputStream(jarFile));
        for (CtClass ctClass : box) {
            ctClass.setModifiers(AccessFlag.setPublic(ctClass.getModifiers()));
            if (isNeedInsertClass(ctClass.getName())){
                for (CtField ctField:ctClass.getDeclaredFields()){
                    int modifiers = ctField.getModifiers();
                    if (AccessFlag.isPackage(modifiers)){
                        modifiers = AccessFlag.setPublic(modifiers);
                        ctField.setModifiers(modifiers);
                    }
                }
            }
            if (isNeedInsertClass(ctClass.getName()) && !(ctClass.isInterface() || ctClass.getDeclaredMethods().length < 1)) {
                zipFile(transformCode(ctClass.toBytecode(), ctClass.getName().replaceAll("\\.", "/")), outStream, ctClass.getName().replaceAll("\\.", "/") + ".class");
            } else {
                zipFile(ctClass.toBytecode(), outStream, ctClass.getName().replaceAll("\\.", "/") + ".class");

            }
        }
        outStream.close();
    }

    public static void insertRobsutProxyCode(GeneratorAdapter mv, String className, String desc, Type returnType, boolean isStatic) {

    }

    private class InsertMethodBodyAdapter extends ClassVisitor implements Opcodes {

        public InsertMethodBodyAdapter() {
            super(Opcodes.ASM5);
        }

        ClassWriter classWriter;
        private String className;
        //this maybe change in the future
        private Map<String, Boolean> isNeedInsertCodeMethodMap;

        public InsertMethodBodyAdapter(ClassWriter cw, String className, Map<String, Boolean> isNeedInsertCodeMethodMap) {
            super(Opcodes.ASM5, cw);
            this.classWriter = cw;
            this.className = className;
            this.isNeedInsertCodeMethodMap = isNeedInsertCodeMethodMap;
            classWriter.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, Constants.INSERT_FIELD_NAME, Type.getDescriptor(ChangeQuickRedirect.class), null, null);
        }


        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
            int originAccess = access;
            //把所有的package方法都改成public
            if (isProtect(access) ||  AsmUtils.CONSTRUCTOR.equals(name) || ASMAccessUtils.isPackage(access)) {
                access = setPublic(access);
            }
            //
            MethodVisitor mv = super.visitMethod(access, name,
                    desc, signature, exceptions);
            if (AsmUtils.CONSTRUCTOR.equals(name)) {
                final int tempAccess = access;
                final String tempDesc = desc;
                final String tempName = name;
                boolean isAnonymousOrLambda = AnonymousUtils.isAnonymousInnerClass(className);
                if (isAnonymousOrLambda) {
                    //ignore
                    //empty <init> ignore // TODO: 17/9/1
                } else {
                    mv = new AdviceAdapter(Opcodes.ASM5, mv, access, name, desc) {
                        @Override
                        protected void onMethodEnter() {
                            super.onMethodEnter();
                            StringBuilder parameters = new StringBuilder();
                            Type[] types = Type.getArgumentTypes(tempDesc);
                            for (Type type : types) {
                                parameters.append(type.getClassName()).append(",");
                            }
                            if (parameters.length() > 0 && parameters.charAt(parameters.length() - 1) == ',') {
                                parameters.deleteCharAt(parameters.length() - 1);
                            }

                            String key = className.replace('/', '.') + "." + tempName + "(" + parameters.toString() + ")";
                            String methodId = RobustMethodId.getMethodId(key);
                            methodMap.put(key, methodId);

                            List<Type> paramsTypeClass = new ArrayList<>();
                            Type returnType = Type.getReturnType(tempDesc);
                            Type[] argsType = Type.getArgumentTypes(tempDesc);
                            for (Type type : argsType) {
                                paramsTypeClass.add(type);
                            }

                            new MethodBodyInsertor(mv, className, tempDesc, isStatic(tempAccess), methodId, tempName, tempAccess).visitCode();
                        }
                    };
                }

                return mv;
            }

            boolean needInsertCode = true;

            boolean ajcClosure1 = AnonymousUtils.isAnonymousInnerClass_$AjcClosure1(className);
            if (ajcClosure1) {
                //ignore ajcClosure
                needInsertCode = false;
            }
            if (name.startsWith("ajc$")){
                //ignore ajc$preClinit()
                needInsertCode = false;
            }
            if (name.contains("_")) {
                //ignore getSystemService_aroundBody17$advice
                needInsertCode = false;
            }
            if (needInsertCode && isStatic(originAccess)) {
                //静态方法必须插桩 , access$000 access$lambda$0 是静态方法，需要排除
                if (((access & Opcodes.ACC_SYNTHETIC) != 0) && ((access & Opcodes.ACC_PRIVATE) == 0)) {
                    needInsertCode = false;
                } else {
                    needInsertCode = true;
                }
            }

            if (needInsertCode){
                needInsertCode = isMethodNeedInsertCode(originAccess, name, desc, isNeedInsertCodeMethodMap);
            }

            if (name.startsWith("lambda$")){
                needInsertCode = true;
            }
            if (name.startsWith("access$")){
                needInsertCode = false;
            }
            if (needInsertCode) {
                StringBuilder parameters = new StringBuilder();
                Type[] types = Type.getArgumentTypes(desc);
                for (Type type : types) {
                    parameters.append(type.getClassName()).append(",");
                }
                if (parameters.length() > 0 && parameters.charAt(parameters.length() - 1) == ',') {
                    parameters.deleteCharAt(parameters.length() - 1);
                }

                String key = className.replace('/', '.') + "." + name + "(" + parameters.toString() + ")";
                String methodId = RobustMethodId.getMethodId(key);
                methodMap.put(key, methodId);

                return new MethodBodyInsertor(mv, className, desc, isStatic(access), methodId, name, access);
            } else {
                return mv;
            }

        }


        private boolean isProtect(int access) {
            return (access & Opcodes.ACC_PROTECTED) != 0;
        }

        private boolean isACCSYNTHETIC(int access) {
            return (access & Opcodes.ACC_SYNTHETIC) != 0;
        }


        private int setPublic(int access) {
            return (access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        }

        //非静态方法走这个逻辑
        private boolean isMethodNeedInsertCode(int access, String name, String desc, Map<String, Boolean> isNeedInsertCodeMethodMap) {
            //类初始化函数和构造函数过滤,构造函数前面已经完成代码插入了
            //对于类CLASS_INITIALIZER方法需要单独插桩 TODO
            if (AsmUtils.CLASS_INITIALIZER.equals(name) || AsmUtils.CONSTRUCTOR.equals(name)) {
                return false;
            }
            // synthetic 方法暂时不aop 比如AsyncTask 会生成一些同名 synthetic方法,对synthetic 以及private的方法也插入的代码，主要是针对lambda表达式
            // 桥方法、编译器自动生成方法不插桩 这里的access已经在之前改成Opcodes.ACC_PUBLIC了
            if (((access & Opcodes.ACC_SYNTHETIC) != 0) && ((access & Opcodes.ACC_PRIVATE) == 0)) {
                return false;
            }
            if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                return false;
            }
            if ((access & Opcodes.ACC_NATIVE) != 0) {
                return false;
            }
            if ((access & Opcodes.ACC_INTERFACE) != 0) {
                return false;
            }


            /*if ((access & Opcodes.ACC_DEPRECATED) != 0) {
                return false;
            }*/

            //过滤不需要patch的方法
            if (isExceptMethodLevel && exceptMethodList != null) {
                for (String item : exceptMethodList) {
                    if (name.matches(item)) {
                        return false;
                    }
                }
            }

            //指定需要patch的方法
            if (isHotfixMethodLevel && hotfixMethodList != null) {
                for (String item : hotfixMethodList) {
                    if (name.matches(item)) {
                        return true;
                    }
                }
            }


            //如果是override方法，插桩 //如果是空方法?

            boolean isMethodInvoke = isNeedInsertCodeMethodMap.getOrDefault(name + desc, true);
            //遍历指令类型，
            if (isMethodInvoke) {
                return true;
            } else {
                return false;
            }
        }


        class MethodBodyInsertor extends GeneratorAdapter implements Opcodes {
            private String className;
            private Type[] argsType;
            private Type returnType;
            List<Type> paramsTypeClass = new ArrayList();
            boolean isStatic;
            //目前methodid是int类型的，未来可能会修改为String类型的，这边进行了一次强转
            String methodId;

            public MethodBodyInsertor(MethodVisitor mv, String className, String desc, boolean isStatic, String methodId, String name, int access) {
                super(Opcodes.ASM5, mv, access, name, desc);
                this.className = className;
                this.returnType = Type.getReturnType(desc);
                Type[] argsType = Type.getArgumentTypes(desc);
                for (Type type : argsType) {
                    paramsTypeClass.add(type);
                }
                this.isStatic = isStatic;
                this.methodId = methodId;
            }


            @Override
            public void visitCode() {
                RobustAsmUtils.createInsertCode(this, className, paramsTypeClass, returnType, isStatic, methodId);
            }
        }
    }

    public static boolean isStatic(int access) {
        return (access & Opcodes.ACC_STATIC) != 0;
    }

    public byte[] transformCode2(byte[] b1, String className) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        InsertMethodBodyAdapter insertMethodBodyAdapter = new InsertMethodBodyAdapter(cw, className, new HashMap());
        ClassReader cr = new ClassReader(b1);
        cr.accept(insertMethodBodyAdapter, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    public byte[] transformCode(byte[] b1, String className) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        ClassReader cr = new ClassReader(b1);

        ClassNode classNode = new ClassNode();
        Map<String, Boolean> isNeedInsertCodeMethodMap = new HashMap<>();
        cr.accept(classNode, 0);
        final List<MethodNode> methods = classNode.methods;
        for (MethodNode m : methods) {
            InsnList inList = m.instructions;
            if (OverrideMethodChecker.isOverrideMethod(m.name)) {
                isNeedInsertCodeMethodMap.put(m.name + m.desc, true);
            } else if (FieldGetterChecker.isGetterMethod(classNode, m)) {
                isNeedInsertCodeMethodMap.put(m.name + m.desc, false);
            } else if (m.maxStack > 90 || inList.size() > 200) {//普通getter setter 方法大概在12-15行
                isNeedInsertCodeMethodMap.put(m.name + m.desc, true);
            } else {
                boolean isMethodInvoke = false;
                for (int i = 0; i < inList.size(); i++) {
                    if (inList.get(i).getType() == AbstractInsnNode.METHOD_INSN) {
                        isMethodInvoke = true;
                    }
                }
                isNeedInsertCodeMethodMap.put(m.name + m.desc, isMethodInvoke);
            }
        }


        InsertMethodBodyAdapter insertMethodBodyAdapter = new InsertMethodBodyAdapter(cw, className, isNeedInsertCodeMethodMap);
        cr.accept(insertMethodBodyAdapter, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }


    public static void main(String[] args) throws IOException {

    }

    private void printlnMap(Map<String, Boolean> map) {
        for (Map.Entry<String, Boolean> entry : map.entrySet()) {
            System.out.println("Key = " + entry.getKey() + ", Value = " + entry.getValue());

        }
    }

}
