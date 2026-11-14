package cn.minims.minidocs.auth.support;

import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * 人机验证图的绘制。
 *
 * <p>画布是「水彩底图 + 若干彩色几何图形」：图形由 {@link Shape} 与颜色两个维度区分，
 * 六个互不相同，验证时要求按提示的顺序点其中三个。</p>
 *
 * <p>为什么不用字符而用图形：字符要么依赖字体文件（容器里不一定有，缺字体时中文会画成方框），
 * 要么改成英文数字，与站点气质不搭；几何图形用 {@link Path2D} 直接画出来，
 * 不依赖任何外部字体或素材，深浅配色也能精确对齐品牌色。</p>
 *
 * <p>底图复用的是登录验证码那套薄荷水彩（{@code resources/captcha/bg-*.png}），
 * 它们在 600x360 上作画，这里纵向裁中段，留出 2.4:1 的横幅比例 ——
 * 登录卡片里图片按表单宽度缩放，比例太方会吃掉半屏高度。</p>
 */
public final class GraphCaptchaArt {

    /** 画布宽：与底图同宽，前端按表单宽度等比缩放。 */
    public static final int WIDTH = 600;
    /** 画布高：2.4:1 的横幅，缩到 340px 宽时约 142px 高。 */
    public static final int HEIGHT = 250;
    /** 底图 600x360，纵向裁掉上下各 55 像素正好是 250。 */
    private static final int CROP_TOP = 55;

    /** 图形基准外接半径。缩到 340px 宽时直径约 45px，手指点得中，又不至于挤在一起。 */
    public static final int RADIUS = 42;

    /**
     * 图形配色。
     *
     * <p>全部压在中低饱和：既要能从薄荷底图上跳出来，又不能比品牌色更抢眼。
     * 青绿占两个（主色系），其余四个当区分用的辅助色 —— 六个图形两两之间
     * 色相差得足够远，色觉不敏感的人还能靠形状分辨。</p>
     */
    private static final String[] PALETTE = {
            "#0B5C4F", // 深青
            "#1C7086", // 湖蓝
            "#3E6C9B", // 靛蓝
            "#A15E3A", // 赭石
            "#8A5A7D", // 藕紫
            "#A9832B", // 琥珀
    };

    private static final int BACKGROUND_COUNT = 6;

    private static final List<BufferedImage> BACKGROUNDS = loadBackgrounds();

    private GraphCaptchaArt() {
    }

    /** 可点选的图形种类。{@link #id()} 与前端 SVG 图标的名字一一对应。 */
    public enum MarkShape {
        CIRCLE, TRIANGLE, SQUARE, DIAMOND, STAR, HEXAGON;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 一个画在底图上的图形。
     *
     * @param x     中心横坐标（原图坐标，前端提交的点击也要换算到这套坐标）
     * @param angle 旋转角度（度）。整齐正放像贴纸，随手撒上去的角度更自然
     */
    public record Mark(MarkShape shape, String color, int x, int y, int radius, double angle) {
    }

    /** 配色表，交给服务层随机分配。 */
    public static String[] palette() {
        return PALETTE.clone();
    }

    public static int backgroundCount() {
        return BACKGROUNDS.size();
    }

    /**
     * 把图形画到随机一张水彩底图上。
     *
     * @param seed 背景序号，取 {@code % backgroundCount()}
     */
    public static BufferedImage render(List<Mark> marks, int seed) {
        BufferedImage canvas = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            BufferedImage background = BACKGROUNDS.get(Math.floorMod(seed, BACKGROUNDS.size()));
            g.drawImage(background.getSubimage(0, CROP_TOP, WIDTH, HEIGHT), 0, 0, null);

            // 一层极淡的白雾：水彩本身明度不一，图形压在深色处容易糊，铺这么一层
            // 能把底子提亮几度；量要小，否则水彩层次就洗没了
            g.setColor(new Color(255, 255, 255, 10));
            g.fillRect(0, 0, WIDTH, HEIGHT);

            marks.forEach(mark -> draw(g, mark));
        } finally {
            g.dispose();
        }
        return canvas;
    }

    private static void draw(Graphics2D g, Mark mark) {
        Shape shape = shapeOf(mark);
        Color base = Color.decode(mark.color());

        // 投影：几层递减的半透明同形状垂直下移，模拟柔和落影。
        // 阴影不跟着图形转 —— 光从上面来，撒一把形状各异的物件，影子方向也该是一致的
        for (int step = 5; step >= 1; step--) {
            g.setColor(new Color(15, 47, 41, 7));
            g.translate(0, step * 1.2);
            g.fill(shape);
            g.translate(0, -step * 1.2);
        }

        // 主体整体旋转一个随手角度，别让六个图形像排版出来的
        java.awt.geom.AffineTransform origin = g.getTransform();
        g.rotate(Math.toRadians(mark.angle()), mark.x(), mark.y());

        // 上浅下深，让平面图形有一点体积感
        g.setPaint(new GradientPaint(0, mark.y() - mark.radius(), lighten(base, 0.26),
                0, mark.y() + mark.radius(), darken(base, 0.1)));
        g.fill(shape);

        // 左上角的高光，水彩纸上的反光
        g.setPaint(new GradientPaint(
                mark.x() - mark.radius(), mark.y() - mark.radius(), new Color(255, 255, 255, 84),
                mark.x() + mark.radius() * 0.2f, mark.y() + mark.radius() * 0.2f, new Color(255, 255, 255, 0)));
        g.fill(shape);

        // 白色描边：图形压在水彩纹理上时，边缘靠这一圈才干净。
        // 线宽按原图给足 2px —— 缩到 340px 宽只剩 1.1px，再细就看不见了；
        // 必须跟主体一起旋转，否则描边与色块错位
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(255, 255, 255, 206));
        g.draw(shape);
        g.setTransform(origin);
    }

    private static Shape shapeOf(Mark mark) {
        double x = mark.x();
        double y = mark.y();
        double r = mark.radius();
        return switch (mark.shape()) {
            case CIRCLE -> new Ellipse2D.Double(x - r * 0.94, y - r * 0.94, r * 1.88, r * 1.88);
            // 系数按「视觉面积」配平：同样外接半径下，三角形的面积只有圆的一半，
            // 星形更小，方形和六边形则显得更满，不校正就是一堆大小不一的色块
            case SQUARE -> {
                double half = r * 0.86;
                yield new RoundRectangle2D.Double(x - half, y - half, half * 2, half * 2, 9, 9);
            }
            case TRIANGLE -> regularPolygon(x, y, r * 1.14, 3, -Math.PI / 2);
            case DIAMOND -> polygon(x, y - r * 1.1, x + r * 0.94, y, x, y + r * 1.1, x - r * 0.94, y);
            case STAR -> star(x, y, r * 1.26, r * 0.53);
            case HEXAGON -> regularPolygon(x, y, r * 1.0, 6, -Math.PI / 2);
        };
    }

    private static Shape regularPolygon(double cx, double cy, double r, int sides, double startAngle) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < sides; i++) {
            double angle = startAngle + i * 2 * Math.PI / sides;
            double px = cx + r * Math.cos(angle);
            double py = cy + r * Math.sin(angle);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.closePath();
        return path;
    }

    private static Shape star(double cx, double cy, double outer, double inner) {
        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < 10; i++) {
            double r = i % 2 == 0 ? outer : inner;
            double angle = -Math.PI / 2 + i * Math.PI / 5;
            double px = cx + r * Math.cos(angle);
            double py = cy + r * Math.sin(angle);
            if (i == 0) {
                path.moveTo(px, py);
            } else {
                path.lineTo(px, py);
            }
        }
        path.closePath();
        return path;
    }

    private static Shape polygon(double... coords) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(coords[0], coords[1]);
        for (int i = 2; i < coords.length; i += 2) {
            path.lineTo(coords[i], coords[i + 1]);
        }
        path.closePath();
        return path;
    }

    private static Color lighten(Color color, double amount) {
        return new Color(
                (int) Math.round(color.getRed() + (255 - color.getRed()) * amount),
                (int) Math.round(color.getGreen() + (255 - color.getGreen()) * amount),
                (int) Math.round(color.getBlue() + (255 - color.getBlue()) * amount));
    }

    private static Color darken(Color color, double amount) {
        return new Color(
                (int) Math.round(color.getRed() * (1 - amount)),
                (int) Math.round(color.getGreen() * (1 - amount)),
                (int) Math.round(color.getBlue() * (1 - amount)));
    }

    private static List<BufferedImage> loadBackgrounds() {
        BufferedImage[] images = new BufferedImage[BACKGROUND_COUNT];
        for (int i = 0; i < BACKGROUND_COUNT; i++) {
            String path = "captcha/bg-" + (i + 1) + ".png";
            try (InputStream in = new ClassPathResource(path).getInputStream()) {
                images[i] = ImageIO.read(in);
            } catch (IOException e) {
                throw new UncheckedIOException("验证码底图读取失败：" + path, e);
            }
            if (images[i] == null) {
                throw new IllegalStateException("验证码底图不是可解析的图片：" + path);
            }
        }
        return List.of(images);
    }
}
