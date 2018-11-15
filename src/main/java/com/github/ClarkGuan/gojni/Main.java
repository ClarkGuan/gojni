package com.github.ClarkGuan.gojni;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class Main {
    public static void main(String[] args) {
        String packageName;
        String codeFile;
        String outputDir;

        CommandLineParser parser = new DefaultParser();
        Options options = new Options();
        options.addOption("p", true, "Go package name");
        options.addOption("o", true, "output directory");
        try {
            CommandLine commandLine = parser.parse(options, args);
            packageName = commandLine.getOptionValue("p", null);
            outputDir = commandLine.getOptionValue("o", ".");
            if (commandLine.getArgs().length == 0) {
                System.err.println("没有指定 java 源文件");
                return;
            }
            codeFile = commandLine.getArgs()[0];
            if (!codeFile.endsWith(".java")) {
                System.err.println(codeFile + " 不是 java 源文件");
                return;
            }
        } catch (ParseException e) {
            System.err.println("解析错误");
            return;
        }

        try (FileInputStream fis = new FileInputStream(codeFile)) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(8124);
            int count;
            byte[] buf = new byte[512];
            while ((count = fis.read(buf)) != -1) {
                baos.write(buf, 0, count);
            }
            new FileGenerator().generate(packageName, baos.toString().toCharArray(), new File(outputDir));
        } catch (IOException e) {
            System.err.println("生成错误");
            e.printStackTrace();
        }
    }

}
