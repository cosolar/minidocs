package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.CaptchaDtos.ChallengeVO;
import cn.minims.minidocs.auth.dto.CaptchaDtos.PickRequest;
import cn.minims.minidocs.auth.dto.CaptchaDtos.PickVO;
import cn.minims.minidocs.auth.dto.CaptchaDtos.TargetVO;
import cn.minims.minidocs.auth.support.GraphCaptchaArt;
import cn.minims.minidocs.auth.support.GraphCaptchaArt.Mark;
import cn.minims.minidocs.auth.support.GraphCaptchaArt.MarkShape;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 人机验证：给一张「水彩底 + 六个彩色图形」的图，要求按提示顺序点其中三个。
 *
 * <p>跟滑块相比，这一版去掉了拖拽和浮层 —— 登录卡片只有四百来像素宽，
 * 拖拽时的拼图面板无论贴在原地还是浮到屏幕中央都会挡住表单，而点选是原地完成的。</p>
 *
 * <p><b>为什么答案不下发前端。</b> 图形坐标是这道题的全部答案，一旦随响应发给浏览器，
 * 脚本读一下内存或 DOM 就能直接点中，校验就退化成了一次形式。
 * 所以前端只知道「要点哪个形状、什么颜色」，点下去由这里判定 ——
 * 一次点击一次往返，代价是每次几十毫秒，换来的是答案不出服务端。</p>
 *
 * <p><b>一张图只给一次机会。</b> 点错立刻作废（无论前端是否还有别的动作），
 * 与滑块「滑歪就得重来」的规则一致，也堵住「拿同一张图反复试探坐标」的路子。
 * 缓存放进程内：本部署是单容器，重启后验证图全丢（用户重点一次即可），
 * 免得为了几十 KB 的临时数据引一个 Redis。</p>
 */
@Slf4j
@Service
public class GraphCaptchaService {

    /** 验证图有效期：从取图到点完三下，2 分钟足够，再久就是给试探留窗口。 */
    private static final Duration TTL = Duration.ofMinutes(2);
    /** 需要按顺序点几个。 */
    private static final int TARGET_COUNT = 3;
    /** 命中判定：以图形半径为基准放宽一倍半，允许手指落在图形边缘外一点。 */
    private static final double HIT_RADIUS_FACTOR = 1.5;
    /** 六个图形排成 3 列 2 行，格宽 200、行距 118：行的中心落在 66 与 184，上下各留够图形半径。 */
    private static final int COLUMN_STEP = 200;
    private static final int ROW_STEP = 118;

    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** 一次验证的进行中状态。 */
    private static final class Challenge {
        private final List<Mark> targets;
        private final long expireAt;
        private int cursor;

        private Challenge(List<Mark> targets, long expireAt) {
            this.targets = targets;
            this.expireAt = expireAt;
        }
    }

    /** 判定结果，给 controller 拼响应。 */
    public record Outcome(boolean accepted, int progress, double hitX, double hitY, boolean done) {
        static Outcome rejected() {
            return new Outcome(false, 0, 0, 0, false);
        }
    }

    /** 出一张新题。 */
    public ChallengeVO issue() {
        prune();
        List<Mark> marks = layout();
        List<Mark> targets = pickTargets(marks);
        String id = newId();
        challenges.put(id, new Challenge(targets, System.currentTimeMillis() + TTL.toMillis()));
        BufferedImage image = GraphCaptchaArt.render(marks, random.nextInt(GraphCaptchaArt.backgroundCount()));
        List<TargetVO> hints = targets.stream()
                .map(mark -> new TargetVO(mark.shape().id(), mark.color()))
                .toList();
        return new ChallengeVO(id, dataUrl(image), GraphCaptchaArt.WIDTH, GraphCaptchaArt.HEIGHT, hints);
    }

    /**
     * 判定一次点击。
     *
     * <p>顺序必须与提示一致：点对了推进一格，点错了整张作废。</p>
     */
    public Outcome pick(PickRequest request) {
        Challenge challenge = challenges.get(request.id());
        if (challenge == null || challenge.expireAt < System.currentTimeMillis()) {
            challenges.remove(request.id());
            return Outcome.rejected();
        }
        synchronized (challenge) {
            // 同一张图被并发点击时（连点、脚本并发），只能有一次生效
            if (challenge.cursor >= TARGET_COUNT || !challenges.containsKey(request.id())) {
                return Outcome.rejected();
            }
            Mark expected = challenge.targets.get(challenge.cursor);
            double distance = Math.hypot(request.x() - expected.x(), request.y() - expected.y());
            if (distance > expected.radius() * HIT_RADIUS_FACTOR) {
                // 点错就作废：留着这张图，等于允许沿着同一张图反复试位置
                challenges.remove(request.id());
                log.debug("人机验证点错 position=({}, {}) expected={} distance={}",
                        request.x(), request.y(), expected.shape().id(), Math.round(distance));
                return Outcome.rejected();
            }
            challenge.cursor++;
            boolean done = challenge.cursor >= TARGET_COUNT;
            if (done) {
                challenges.remove(request.id());
            }
            return new Outcome(true, challenge.cursor, expected.x(), expected.y(), done);
        }
    }

    /**
     * 仅供测试读取当前题的答案。
     *
     * <p>包内可见，不从任何接口暴露 —— 接口一旦吐坐标，这层校验就退化成走形式了。</p>
     */
    List<Mark> targetsOf(String id) {
        Challenge challenge = challenges.get(id);
        return challenge == null ? List.of() : challenge.targets;
    }

    /** 布局：六个图形各占一格，格内随机偏移，保证互不重叠。 */
    private List<Mark> layout() {
        MarkShape[] shapes = MarkShape.values().clone();
        String[] colors = GraphCaptchaArt.palette();
        shuffle(shapes);
        shuffle(colors);

        List<Mark> marks = new ArrayList<>(shapes.length);
        for (int i = 0; i < shapes.length; i++) {
            int column = i % 3;
            int row = i / 3;
            int x = 100 + column * COLUMN_STEP + random.nextInt(57) - 28;
            int y = 66 + row * ROW_STEP + random.nextInt(9) - 4;
            // 随手角度只给三角形、菱形、星形：方块一转就成了菱形，两者混淆比「显得自然」更亏；
            // 圆形和六边形转起来本来就看不出来，也就没必要加
            boolean turns = shapes[i] == MarkShape.TRIANGLE || shapes[i] == MarkShape.DIAMOND
                    || shapes[i] == MarkShape.STAR;
            double angle = turns ? random.nextInt(37) - 18 : 0;
            marks.add(new Mark(shapes[i], colors[i], x, y, GraphCaptchaArt.RADIUS, angle));
        }
        return marks;
    }

    /** 从六个图形里抽三个当目标，顺序也随机。 */
    private List<Mark> pickTargets(List<Mark> marks) {
        List<Mark> pool = new ArrayList<>(marks);
        shuffle(pool);
        return List.copyOf(pool.subList(0, TARGET_COUNT));
    }

    /** 顺手清掉过期条目；取图频率很低，每次全扫一遍的开销可以忽略。 */
    private void prune() {
        long now = System.currentTimeMillis();
        challenges.entrySet().removeIf(entry -> entry.getValue().expireAt < now);
    }

    private String newId() {
        byte[] buffer = new byte[16];
        random.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    private static String dataUrl(BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(96 * 1024)) {
            ImageIO.write(image, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("验证图编码失败", e);
        }
    }

    private void shuffle(MarkShape[] values) {
        Collections.shuffle(Arrays.asList(values), random);
    }

    private void shuffle(String[] values) {
        Collections.shuffle(Arrays.asList(values), random);
    }

    private void shuffle(List<?> values) {
        Collections.shuffle(values, random);
    }
}
