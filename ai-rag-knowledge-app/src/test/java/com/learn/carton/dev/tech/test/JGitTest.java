package com.learn.carton.dev.tech.test;


import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.PathResource;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Carton
 * @date 2025/12/8 11:08
 * @description TODO: JGit单元测试
 */

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class JGitTest {

    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private PgVectorStore pgVectorStore;

    @Test
    public void test() throws Exception {

        // 让 Java 走你的代理
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7897");
        System.setProperty("http.proxyHost", "127.0.0.1");
        System.setProperty("http.proxyPort", "7897");

        String repoUrl = "https://github.com/555-zlq/Big-Market-Front";
        String username = "555-zlq";
        String password = "";

        String localPath = "./cloned-repo";
        log.info("克隆路径：" + new File(localPath).getAbsolutePath());

        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(username, password))
                .call();

        git.close();
    }

    @Test
    public void test_file() throws IOException {
        Files.walkFileTree(Paths.get("./cloned-repo"), new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // 排除git和idea等不需要处理的目录
                String name = dir.getFileName().toString();
                if (name.equals(".git") || name.equals(".idea") || name.equals("node_modules") || name.equals("target")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                log.info("文件路径：{}", file);

                // 1. 跳过可能导致问题的二进制或非文本文件
                if (isBinary(file)) {
                    log.info("跳过二进制文件: {}", file);
                    return FileVisitResult.CONTINUE;
                }

                try {
                    PathResource resource = new PathResource(file);
                    TikaDocumentReader reader = new TikaDocumentReader(resource);

                    List<Document> documents = reader.get();

                    // 2. 避免 content == null 的情况
                    documents = documents.stream()
                            .filter(doc -> doc.getContent() != null && !doc.getContent().trim().isEmpty())
                            .collect(Collectors.toList());

                    if (documents.isEmpty()) {
                        log.info("内容为空，跳过文件: {}", file);
                        return FileVisitResult.CONTINUE;
                    }

                    // 3. 文本切片
                    List<Document> documentList = tokenTextSplitter.apply(documents);

                    // 4. 加标签
                    documentList.forEach(doc ->
                            doc.getMetadata().put("knowledge", "Big-Market-Front")
                    );

                    // 5. 写入向量库
                    pgVectorStore.accept(documentList);

                } catch (Exception e) {
                    // 任何无法被 Tika 处理的文件都跳过
                    log.warn("解析文件失败，已跳过: {}，原因：{}", file, e.getMessage());
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 判断是否为二进制文件
     */
    private boolean isBinary(Path file) {
        String fileName = file.getFileName().toString().toLowerCase();

        // 常见二进制扩展名
        String[] binaryExt = {
                "png", "jpg", "jpeg", "gif", "ico", "zip", "tar", "gz", "exe",
                "dll", "class", "jar", "war", "pdf", "mp3", "mp4", "avi", "mov"
        };

        return Arrays.stream(binaryExt).anyMatch(fileName::endsWith);
    }



}
