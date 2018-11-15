package com.github.ClarkGuan.gojni;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.stringtemplate.v4.ST;
import org.stringtemplate.v4.STGroupFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

class FileGenerator {
    private String packageName = "main";
    private String javaPackageName;
    private List<String> imports;
    private List<TypeGenerator> classes;

    FileGenerator() {
    }

    void generate(String packageName, char[] code, File dir) throws IOException {
        // 包名准备
        if (packageName != null && packageName.length() > 0) {
            this.packageName = packageName;
        }

        // 写入文件的准备
        if (dir == null || !dir.isDirectory()) {
            throw new IOException(String.format("找不到目录：%s", dir.getAbsolutePath()));
        }

        String fileName = this.packageName.equals("main") ? "libs" : this.packageName;
        File cCode = new File(dir, fileName + ".c");
        File goCode = new File(dir, fileName + ".go");
        if (cCode.exists() && cCode.isFile()) {
            cCode.delete();
        }
        if (goCode.exists() && goCode.isFile()) {
            goCode.delete();
        }

        ASTParser astParser = ASTParser.newParser(AST.JLS10);
        Hashtable<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.VERSION_1_8, options);
        astParser.setCompilerOptions(options);
        astParser.setSource(code);
        astParser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit compilationUnit = (CompilationUnit) astParser.createAST(null);
        IProblem[] problems = compilationUnit.getProblems();
        if (problems != null && problems.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (IProblem p : problems) {
                sb.append(p.getMessage()).append('\n');
            }
            throw new IOException(sb.toString());
        }

        PackageDeclaration packageDeclaration = compilationUnit.getPackage();
        javaPackageName = packageDeclaration.getName().getFullyQualifiedName();

        List<ImportDeclaration> importDeclarations = compilationUnit.imports();
        if (importDeclarations != null && !importDeclarations.isEmpty()) {
            for (ImportDeclaration declaration : importDeclarations) {
                if (declaration.isOnDemand()) {
                    throw new RuntimeException(String.format("目前还处理不了 import * 的情况, %s",
                            declaration.getName().getFullyQualifiedName()));
                }

                if (imports == null) {
                    imports = new ArrayList<>();
                }
                imports.add(declaration.getName().getFullyQualifiedName());
            }
        } else {
            imports = new ArrayList<>(0);
        }

        List<AbstractTypeDeclaration> typeDeclarations = compilationUnit.types();
        STGroupFile stGroupFile = new STGroupFile(getClass().getResource("/jni.stg"),
                "utf-8", '<', '>');
        classes = new ArrayList<>();
        for (AbstractTypeDeclaration td : typeDeclarations) {
            if (td instanceof TypeDeclaration) {
                TypeGenerator generator = TypeGenerator.from(this, (TypeDeclaration) td);
                if (generator != null) {
                    classes.add(generator);
                }
            }
        }

        ST st = stGroupFile.getInstanceOf("header");
        st.add("code", this);
        writeTo(st.render(), cCode);

        st = stGroupFile.getInstanceOf("goCode");
        st.add("code", this);
        writeTo(st.render(), goCode);
    }

    private static void writeTo(String content, File file) throws IOException {
        try (FileOutputStream outputStream = new FileOutputStream(file)) {
            outputStream.write(content.getBytes());
        }
    }

    public String getPackageName() {
        return packageName;
    }

    String getJavaPackageName() {
        return javaPackageName;
    }

    public List<TypeGenerator> getClasses() {
        return classes;
    }

    List<String> getImports() {
        return imports;
    }
}

class TypeGenerator {
    private FileGenerator fileGenerator;
    private TypeDeclaration typeDeclaration;
    private List<FunctionGenerator> jniMethods = new ArrayList<>();

    private String name;

    private TypeGenerator(FileGenerator fileGenerator, TypeDeclaration typeDeclaration) {
        this.fileGenerator = fileGenerator;
        this.typeDeclaration = typeDeclaration;
    }

    private static List<MethodDeclaration> findJniMethods(TypeDeclaration declaration) {
        List<MethodDeclaration> methodDeclarations = new ArrayList<>();
        MethodDeclaration[] methods = declaration.getMethods();
        if (methods == null || methods.length == 0) return methodDeclarations;
        for (MethodDeclaration md : methods) {
            if (Modifier.isNative(md.getModifiers())) {
                methodDeclarations.add(md);
            }
        }
        return methodDeclarations;
    }

    static TypeGenerator from(FileGenerator fileGenerator, TypeDeclaration typeDeclaration) {
        List<MethodDeclaration> jniMethods = findJniMethods(typeDeclaration);
        if (!jniMethods.isEmpty()) {
            TypeGenerator typeGenerator = new TypeGenerator(fileGenerator, typeDeclaration);
            int i = 0;
            for (MethodDeclaration md : jniMethods) {
                typeGenerator.jniMethods.add(new FunctionGenerator(typeGenerator, md, ++i));
            }
            return typeGenerator;
        }
        return null;
    }

    public String getName() {
        if (name == null) {
            name = fileGenerator.getJavaPackageName() + "." + typeDeclaration.getName().getIdentifier();
            name = name.replace('.', '/');
        }

        return name;
    }

    public List<FunctionGenerator> getJniMethods() {
        return jniMethods;
    }

    FileGenerator getFileGenerator() {
        return fileGenerator;
    }
}

class FunctionGenerator {
    private TypeGenerator typeGenerator;
    private MethodDeclaration methodDeclaration;
    private int index;

    private String name;
    private String goName;
    private String signature;
    private String returnType;
    private String javaName;
    private String javaSignature;
    private String goReturnType;
    private String goSignature;
    private String goSignature2;
    private String goReturnType2;
    private String callGoSignature2;
    private String goReturnZero;
    private boolean hasReturnType;

    FunctionGenerator(TypeGenerator typeGenerator,
                      MethodDeclaration methodDeclaration, int index) {
        this.typeGenerator = typeGenerator;
        this.methodDeclaration = methodDeclaration;
        this.index = index;

        {
            StringBuilder sb = new StringBuilder();
            sb.append("jni_")
                    .append(typeGenerator.getFileGenerator().getJavaPackageName().replace('.', '_'))
                    .append('_')
                    .append(methodDeclaration.getName().getIdentifier())
                    .append(index);
            name = sb.toString();
        }

        {
            StringBuilder sb = new StringBuilder();
            sb.append("go_")
                    .append(typeGenerator.getFileGenerator().getJavaPackageName().replace('.', '_'))
                    .append('_')
                    .append(methodDeclaration.getName().getIdentifier())
                    .append(index);
            goName = sb.toString();
        }

        {
            StringBuilder sb = new StringBuilder();
            sb.append("GoUintptr env, ");
            if (Modifier.isStatic(methodDeclaration.getModifiers())) {
                sb.append("GoUintptr clazz");
            } else {
                sb.append("GoUintptr thiz");
            }
            List<SingleVariableDeclaration> variableDeclarations = methodDeclaration.parameters();
            for (SingleVariableDeclaration svd : variableDeclarations) {
                sb.append(", ");
                sb.append(cTypeMap(svd.getType(), false)).append(' ');
                sb.append(svd.getName().getIdentifier());
            }
            signature = sb.toString();
        }

        Type returnType2 = methodDeclaration.getReturnType2();
        returnType = cTypeMap(returnType2, false);
        goReturnType = cTypeMap(returnType2, true);
        goReturnType2 = goTypeMap(returnType2);

        {
            StringBuilder sb = new StringBuilder();

            sb.append("env uintptr, ");
            if (Modifier.isStatic(methodDeclaration.getModifiers())) {
                sb.append("clazz uintptr");
            } else {
                sb.append("thiz uintptr");
            }

            List<SingleVariableDeclaration> variableDeclarations = methodDeclaration.parameters();
            for (SingleVariableDeclaration svd : variableDeclarations) {
                sb.append(", ");
                sb.append(svd.getName().getIdentifier()).append(' ');
                sb.append(cTypeMap(svd.getType(), true));
            }

            goSignature = sb.toString();
        }

        javaName = methodDeclaration.getName().getIdentifier();

        {
            StringBuilder sb = new StringBuilder();
            sb.append('(');
            List<SingleVariableDeclaration> variableDeclarations = methodDeclaration.parameters();
            for (SingleVariableDeclaration svd : variableDeclarations) {
                sb.append(jniTypeMap(svd.getType()));
            }
            sb.append(')');
            sb.append(jniTypeMap(returnType2));
            javaSignature = sb.toString();
        }

        {
            StringBuilder sb = new StringBuilder();
            sb.append("env jni.Env, ");
            if (Modifier.isStatic(methodDeclaration.getModifiers())) {
                sb.append("clazz uintptr");
            } else {
                sb.append("thiz uintptr");
            }

            List<SingleVariableDeclaration> variableDeclarations = methodDeclaration.parameters();
            for (SingleVariableDeclaration svd : variableDeclarations) {
                sb.append(", ");
                sb.append(svd.getName().getIdentifier()).append(' ');
                sb.append(goTypeMap(svd.getType()));
            }
            goSignature2 = sb.toString();
        }

        {
            StringBuilder sb = new StringBuilder();
            sb.append("jni.Env(env), ");
            if (Modifier.isStatic(methodDeclaration.getModifiers())) {
                sb.append("clazz");
            } else {
                sb.append("thiz");
            }

            List<SingleVariableDeclaration> variableDeclarations = methodDeclaration.parameters();
            for (SingleVariableDeclaration svd : variableDeclarations) {
                sb.append(", ").append(svd.getName().getIdentifier());
            }
            callGoSignature2 = sb.toString();
        }

        goReturnZero = goTypeZero(returnType2);
        hasReturnType = returnType2.isPrimitiveType() ? ((PrimitiveType) returnType2).getPrimitiveTypeCode() != PrimitiveType.VOID : true;
    }

    public boolean isHasReturnType() {
        return hasReturnType;
    }

    public String getName() {
        return name;
    }

    public String getGoName() {
        return goName;
    }

    public String getCSignature() {
        return signature;
    }

    public String getCReturnType() {
        return returnType;
    }

    public String getGoReturnType() {
        return goReturnType;
    }

    public String getGoSignature() {
        return goSignature;
    }

    public String getJavaName() {
        return javaName;
    }

    public String getJavaSignature() {
        return javaSignature;
    }

    public String getGoSignature2() {
        return goSignature2;
    }

    public String getGoReturnType2() {
        return goReturnType2;
    }

    public String getCallGoSignature2() {
        return callGoSignature2;
    }

    public String getGoReturnZero() {
        return goReturnZero;
    }

    private String jniTypeMap(Type t) {
        if (t.isPrimitiveType()) {
            return jniPrimitiveTypeMap((PrimitiveType) t);
        } else if (t.isArrayType()) {
            return jniArrayTypeMap((ArrayType) t);
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("L");
            sb.append(jniQualifiedTypeMap(t).replace('.', '/'));
            sb.append(";");
            return sb.toString();
        }
    }

    private String jniQualifiedTypeMap(Type t) {
        String name = "";
        if (t.isParameterizedType()) {
            ParameterizedType pt = (ParameterizedType) t;
            return jniQualifiedTypeMap(pt.getType());
        } else if (t.isAnnotatable()) {
            name = t.toString();
        }

        for (String s : typeGenerator.getFileGenerator().getImports()) {
            if (s.endsWith(name)) {
                return s;
            }
        }

        switch (name) {
            case "String":
            case "Class":
            case "Throwable":
            case "AbstractMethodError":
            case "Appendable":
            case "ArithmeticException":
            case "ArrayIndexOutOfBoundsException":
            case "ArrayStoreException":
            case "AssertionError":
            case "AutoCloseable":
            case "Boolean":
            case "BootstrapMethodError":
            case "Byte":
            case "Character":
            case "CharSequence":
            case "ClassCastException":
            case "ClassCircularityError":
            case "ClassFormatError":
            case "ClassLoader":
            case "ClassNotFoundException":
            case "ClassValue":
            case "Cloneable":
            case "CloneNotSupportedException":
            case "Comparable":
            case "Compiler":
            case "Double":
            case "Enum":
            case "EnumConstantNotPresentException":
            case "Error":
            case "Exception":
            case "ExceptionInInitializerError":
            case "Float":
            case "IllegalAccessError":
            case "IllegalAccessException":
            case "IllegalArgumentException":
            case "IllegalMonitorStateException":
            case "IllegalStateException":
            case "IllegalThreadStateException":
            case "IncompatibleClassChangeError":
            case "IndexOutOfBoundsException":
            case "InheritableThreadLocal":
            case "InstantiationError":
            case "InstantiationException":
            case "Integer":
            case "InternalError":
            case "InterruptedException":
            case "Iterable":
            case "LinkageError":
            case "Long":
            case "Math":
            case "NegativeArraySizeException":
            case "NoClassDefFoundError":
            case "NoSuchFieldError":
            case "NoSuchFieldException":
            case "NoSuchMethodError":
            case "NoSuchMethodException":
            case "NullPointerException":
            case "Number":
            case "NumberFormatException":
            case "Object":
            case "OutOfMemoryError":
            case "Package":
            case "Process":
            case "ProcessBuilder":
            case "Readable":
            case "ReflectiveOperationException":
            case "Runnable":
            case "Runtime":
            case "RuntimeException":
            case "RuntimePermission":
            case "SecurityException":
            case "SecurityManager":
            case "Short":
            case "StackOverflowError":
            case "StackTraceElement":
            case "StrictMath":
            case "StringBuffer":
            case "StringBuilder":
            case "StringIndexOutOfBoundsException":
            case "System":
            case "Thread":
            case "ThreadDeath":
            case "ThreadGroup":
            case "ThreadLocal":
            case "TypeNotPresentException":
            case "UnknownError":
            case "UnsatisfiedLinkError":
            case "UnsupportedClassVersionError":
            case "UnsupportedOperationException":
            case "VerifyError":
            case "VirtualMachineError":
            case "Void":
                return "java.lang." + name;
        }

        System.err.println("找不到类型：" + name);
        // 看做是当前 Java Package 的类型
        return typeGenerator.getFileGenerator().getJavaPackageName() + "." + name;
    }

    private String jniArrayTypeMap(ArrayType type) {
        String name = "[";
        Type elementType = type.getElementType();
        name += jniTypeMap(elementType);
        return name;
    }

    private String jniPrimitiveTypeMap(PrimitiveType type) {
        PrimitiveType.Code primitiveTypeCode = type.getPrimitiveTypeCode();
        if (primitiveTypeCode == PrimitiveType.BOOLEAN) {
            return "Z";
        } else if (primitiveTypeCode == PrimitiveType.CHAR) {
            return "C";
        } else if (primitiveTypeCode == PrimitiveType.BYTE) {
            return "B";
        } else if (primitiveTypeCode == PrimitiveType.SHORT) {
            return "S";
        } else if (primitiveTypeCode == PrimitiveType.INT) {
            return "I";
        } else if (primitiveTypeCode == PrimitiveType.LONG) {
            return "J";
        } else if (primitiveTypeCode == PrimitiveType.FLOAT) {
            return "F";
        } else if (primitiveTypeCode == PrimitiveType.DOUBLE) {
            return "D";
        } else if (primitiveTypeCode == PrimitiveType.VOID) {
            return "V";
        }

        throw new RuntimeException();
    }

    private String goTypeMap(Type t) {
        if (t.isPrimitiveType()) {
            return cPrimitiveTypeMap((PrimitiveType) t, true);
        } else {
            return "uintptr";
        }
    }

    private String goTypeZero(Type t) {
        if (t.isPrimitiveType()) {
            PrimitiveType type = (PrimitiveType) t;
            PrimitiveType.Code primitiveTypeCode = type.getPrimitiveTypeCode();
            if (primitiveTypeCode == PrimitiveType.BOOLEAN) {
                return "false";
            } else if (primitiveTypeCode == PrimitiveType.CHAR) {
                return "0";
            } else if (primitiveTypeCode == PrimitiveType.BYTE) {
                return "0";
            } else if (primitiveTypeCode == PrimitiveType.SHORT) {
                return "0";
            } else if (primitiveTypeCode == PrimitiveType.INT) {
                return "0";
            } else if (primitiveTypeCode == PrimitiveType.LONG) {
                return "0";
            } else if (primitiveTypeCode == PrimitiveType.FLOAT) {
                return "0";
            } else if (primitiveTypeCode == PrimitiveType.DOUBLE) {
                return "0";
            } else if (primitiveTypeCode == PrimitiveType.VOID) {
                return "";
            }

            throw new RuntimeException();
        } else {
            return "0";
        }
    }

    private String cTypeMap(Type t, boolean godec) {
        if (t.isPrimitiveType()) {
            return cPrimitiveTypeMap((PrimitiveType) t, godec);
        } else {
            return godec ? "uintptr" : "GoUintptr";
        }
    }

    private String cPrimitiveTypeMap(PrimitiveType type, boolean godec) {
        PrimitiveType.Code primitiveTypeCode = type.getPrimitiveTypeCode();
        if (primitiveTypeCode == PrimitiveType.BOOLEAN) {
            return godec ? "uint8" : "GoUint8";
        } else if (primitiveTypeCode == PrimitiveType.CHAR) {
            return godec ? "uint16" : "GoUint16";
        } else if (primitiveTypeCode == PrimitiveType.BYTE) {
            return godec ? "uint8" : "GoUint8";
        } else if (primitiveTypeCode == PrimitiveType.SHORT) {
            return godec ? "int16" : "GoInt16";
        } else if (primitiveTypeCode == PrimitiveType.INT) {
            return godec ? "int32" : "GoInt32";
        } else if (primitiveTypeCode == PrimitiveType.LONG) {
            return godec ? "int64" : "GoInt64";
        } else if (primitiveTypeCode == PrimitiveType.FLOAT) {
            return godec ? "float32" : "GoFloat32";
        } else if (primitiveTypeCode == PrimitiveType.DOUBLE) {
            return godec ? "float64" : "GoFloat64";
        } else if (primitiveTypeCode == PrimitiveType.VOID) {
            return godec ? "" : "void";
        }

        throw new RuntimeException();
    }
}
