package com.superprogrammer.media.edit.provider;

import com.superprogrammer.media.edit.dto.EditSpec;
import com.superprogrammer.media.edit.dto.MediaProbe;

import java.nio.file.Path;
import java.util.Map;

/**
 * 视频剪辑渲染 provider 抽象。
 *
 * <p>首实现 {@link FfmpegEditProvider}（系统 FFmpeg，ProcessBuilder 参数数组防注入）。
 * 与 {@code MediaGenProvider}（生成，异步任务协议）正交：剪辑是本地同步渲染（输入路径→输出文件）。
 *
 * <p>实现约束：
 * <ul>
 *   <li>{@code render} 同步阻塞（分钟级），由 worker 在独立线程池调用，不得占 Web 线程。</li>
 *   <li>FFmpeg 命令一律走 ProcessBuilder 参数数组（不经 shell）；文件用内部 temp 路径、字幕走 textfile 临时文件——防命令注入。</li>
 *   <li>失败抛异常，stderr 不直接外泄（worker 截断脱敏后入库）。</li>
 * </ul>
 */
public interface MediaEditProvider {

    /**
     * 渲染成片（多轨）。
     *
     * @param spec          剪辑意图（须已由 {@code EditSpecNormalizer.normalize} 规范化成 V2，target/trim 已填充）
     * @param mediaByFileId spec 引用的所有 fileId → worker 已校验归属并 copy 到 temp 的本地路径（去重，同 fileId 多段共用）
     * @param output        输出文件路径（mp4）
     */
    void render(EditSpec spec, Map<String, Path> mediaByFileId, Path output) throws Exception;

    /** ffprobe 探测时长（秒）。非法文件抛异常（调用方据此拒非视频/超限）。 */
    double probeDurationSeconds(Path input) throws Exception;

    /** ffprobe 探测媒体信息（流类型/宽高/时长）。 */
    MediaProbe probe(Path input) throws Exception;
}
