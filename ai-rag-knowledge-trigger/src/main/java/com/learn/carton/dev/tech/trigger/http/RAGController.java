package com.learn.carton.dev.tech.trigger.http;


import com.learn.carton.dev.tech.api.IRagService;
import com.learn.carton.dev.tech.api.response.Response;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.ollama.OllamaChatClient;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.PgVectorStore;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.core.io.PathResource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController()
@CrossOrigin("*")
@RequestMapping("/api/v1/rag/")
public class RAGController implements IRagService {

    @Resource
    private OllamaChatClient ollamaChatClient;
    @Resource
    private TokenTextSplitter tokenTextSplitter;
    @Resource
    private SimpleVectorStore simpleVectorStore;
    @Resource
    private PgVectorStore pgVectorStore;
    @Resource
    private RedissonClient redissonClient;

    @RequestMapping(value = "query_rag_tag_list", method = RequestMethod.GET)
    @Override
    public Response<List<String>> queryRagTagList() {
        RList<String> elements = redissonClient.getList("ragTag");
        return Response.<List<String>>builder()
                .code("0000")
                .info("调用成功")
                .data(elements)
                .build();
    }

    @RequestMapping(value = "file/upload", method = RequestMethod.POST, headers = "content-type=multipart/form-data")
    @Override
    public Response<String> uploadFile(@RequestParam String ragTag, @RequestParam("file") List<MultipartFile> files) {
        log.info("上传知识库开始 {}", ragTag);
        for (MultipartFile file : files) {
            TikaDocumentReader reader = new TikaDocumentReader(file.getResource());

            List<Document> documents = reader.get();
            List<Document> documentSplitterList = tokenTextSplitter.apply(documents);

            documents.forEach(document -> document.getMetadata().put("knowledge", ragTag));
            documentSplitterList.forEach(document -> document.getMetadata().put("knowledge", ragTag));

            pgVectorStore.accept(documentSplitterList);

            RList<String> elements = redissonClient.getList("ragTag");
            if (!elements.contains(ragTag)) {
                elements.add(ragTag);
            }
        }
        log.info("上传知识库完成 {}", ragTag);
        return Response.<String>builder().code("0000").info("调用成功").build();
    }

    @RequestMapping(value = "analyze_git_repository", method = RequestMethod.POST)
    @Override
    public Response<String> analyzeGitRepository(@RequestParam String repoUrl, @RequestParam String userName, @RequestParam String token) throws Exception {
        String localPath = "./git-cloned-repo";
        String repoProjectName = extractProjectName(repoUrl);
        log.info("克隆路径：{}", new File(localPath).getAbsolutePath());

        FileUtils.deleteDirectory(new File(localPath));

        Git git = Git.cloneRepository()
                .setURI(repoUrl)
                .setDirectory(new File(localPath))
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(userName, token))
                .call();

        Files.walkFileTree(Paths.get(localPath), new SimpleFileVisitor<>() {

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
                    documents.forEach(doc -> doc.getMetadata().put("knowledge", repoProjectName));
                    documentList.forEach(doc ->
                            doc.getMetadata().put("knowledge", repoProjectName)
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

        FileUtils.deleteDirectory(new File(localPath));

        RList<String> elements = redissonClient.getList("ragTag");
        if (!elements.contains(repoProjectName)) {
            elements.add(repoProjectName);
        }

        git.close();

        log.info("遍历解析路径，上传完成:{}", repoUrl);

        return Response.<String>builder().code("0000").info("调用成功").build();

    }

    private String extractProjectName(String repoUrl) {
        String[] parts = repoUrl.split("/");
        String projectNameWithGit = parts[parts.length - 1];
        return projectNameWithGit.replace(".git", "");
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
