package com.pcdd.sonovel.core;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Console;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RuntimeUtil;
import cn.hutool.core.util.StrUtil;
import com.pcdd.sonovel.context.BookContext;
import com.pcdd.sonovel.handle.CrawlerPostHandler;
import com.pcdd.sonovel.model.AppConfig;
import com.pcdd.sonovel.model.Book;
import com.pcdd.sonovel.model.Chapter;
import com.pcdd.sonovel.model.SearchResult;
import com.pcdd.sonovel.parse.BookParser;
import com.pcdd.sonovel.parse.ChapterParser;
import com.pcdd.sonovel.parse.SearchParser;
import com.pcdd.sonovel.parse.SearchParserQuanben5;
import com.pcdd.sonovel.util.FileUtils;
import lombok.SneakyThrows;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.fusesource.jansi.AnsiRenderer.render;

/**
 * @author pcdd
 * Created at 2021/6/10
 */
public class Crawler {

    private final AppConfig config;
    private int digitCount;
    private String bookDir;

    public Crawler(AppConfig config) {
        this.config = config;
    }

    /**
     * 搜索小说
     *
     * @param keyword 关键字
     * @return 匹配的小说列表
     */
    public List<SearchResult> search(String keyword) {
        Console.log("<== 正在搜索...");
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();

        List<SearchResult> searchResults;

        if (config.getSourceId() == 6) {
            searchResults = new SearchParserQuanben5(config).parse(keyword);
        } else {
            searchResults = new SearchParser(config).parse(keyword, true);
        }

        stopWatch.stop();
        Console.log("<== 搜索到 {} 条记录，耗时 {} s", searchResults.size(), NumberUtil.round(stopWatch.getTotalTimeSeconds(), 2));

        return searchResults;
    }

    /**
     * 爬取小说
     *
     * @param bookUrl 详情页链接
     * @param toc     章节目录
     */
    @SneakyThrows
    public double crawl(String bookUrl, List<Chapter> toc) {
        digitCount = String.valueOf(toc.size()).length();
        Book book = new BookParser(config).parse(bookUrl);
        BookContext.set(book);

        // 下载临时目录名格式：书名(作者) EXT
        bookDir = FileUtils.sanitizeFileName("%s(%s) %s".formatted(book.getBookName(), book.getAuthor(), config.getExtName().toUpperCase()));
        // 必须 new File()，否则无法使用 . 和 ..
        File dir = FileUtil.mkdir(new File(config.getDownloadPath() + File.separator + bookDir));
        if (!dir.exists()) {
            // C:\Program Files 下创建需要管理员权限
            Console.log(render("""
                    创建下载目录失败：%s
                    1. 检查 config.ini 下载路径是否合法
                    2. 尝试以管理员身份运行（部分目录需要管理员权限）
                    """.formatted(dir), "red"));
            return 0;
        }

        int autoThreads = config.getThreads() == -1 ? RuntimeUtil.getProcessorCount() * 2 : config.getThreads();
        // 创建线程池
        ExecutorService executor = Executors.newFixedThreadPool(autoThreads);
        // 阻塞主线程，用于计时
        CountDownLatch latch = new CountDownLatch(toc.size());

        Console.log("<== 开始下载《{}》（{}） 共计 {} 章 | 线程数：{}", book.getBookName(), book.getAuthor(), toc.size(), autoThreads);
        if (config.getShowDownloadLog() == 0) {
            Console.log("<== 下载日志已关闭，请耐心等待...");
        }
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        ChapterParser chapterParser = new ChapterParser(config);

        // 爬取&下载章节
        toc.forEach(item -> executor.execute(() -> {
            createChapterFile(chapterParser.parse(item, latch));
            if (config.getShowDownloadLog() == 1) {
                Console.log("<== 待下载章节数：{}", latch.getCount());
            }
        }));

        // 阻塞主线程，等待全部章节下载完毕
        latch.await();
        new CrawlerPostHandler(config).handle(dir);
        stopWatch.stop();

        executor.shutdown();
        BookContext.clear();

        return stopWatch.getTotalTimeSeconds();
    }

    /**
     * 保存章节
     */
    private void createChapterFile(Chapter chapter) {
        if (chapter == null) return;

        try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(generateChapterPath(chapter)))) {
            fos.write(chapter.getContent().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Console.error(e);
        }
    }

    private String generateChapterPath(Chapter chapter) {
        String parentPath = config.getDownloadPath() + File.separator + bookDir + File.separator;
        // 文件名下划线前的数字前补零
        String order = digitCount >= String.valueOf(chapter.getOrder()).length()
                ? StrUtil.padPre(chapter.getOrder() + "", digitCount, '0') // 全本下载
                : String.valueOf(chapter.getOrder()); // 非全本下载

        return parentPath + order + switch (config.getExtName()) {
            // 下划线用于兼容，不要删除，见 com/pcdd/sonovel/handle/HtmlTocHandler.java:28
            case "html" -> "_.html";
            case "txt" -> "_" + FileUtils.sanitizeFileName(chapter.getTitle()) + ".txt";
            // 转换前的格式为 html
            case "epub", "pdf" -> "_" + FileUtils.sanitizeFileName(chapter.getTitle()) + ".html";
            default -> throw new IllegalStateException("暂不支持的下载格式: " + config.getExtName());
        };
    }

}