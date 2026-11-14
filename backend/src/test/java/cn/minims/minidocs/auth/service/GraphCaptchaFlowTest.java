package cn.minims.minidocs.auth.service;

import cn.minims.minidocs.auth.dto.CaptchaDtos.ChallengeVO;
import cn.minims.minidocs.auth.dto.CaptchaDtos.PickRequest;
import cn.minims.minidocs.auth.dto.CaptchaDtos.TargetVO;
import cn.minims.minidocs.auth.support.GraphCaptchaArt;
import cn.minims.minidocs.auth.support.GraphCaptchaArt.Mark;
import cn.minims.minidocs.support.MiniDocsTestBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人机验证：出题、按序点选、单次机会。
 *
 * <p>要守住的三条：</p>
 * <ul>
 *   <li>响应里只有形状与颜色，<b>没有坐标</b> —— 坐标就是答案；</li>
 *   <li>必须按提示的顺序点，点错一次整张作废，不能拿同一张图反复试位置；</li>
 *   <li>点过的题立刻销毁，重放 / 换坐标重试都无效。</li>
 * </ul>
 */
@DisplayName("人机验证（点选图形）")
class GraphCaptchaFlowTest extends MiniDocsTestBase {

    @Autowired
    private GraphCaptchaService service;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("出题：一张 PNG、三个目标图形、尺寸固定")
    void issueProducesChallenge() {
        ChallengeVO vo = service.issue();

        assertThat(vo.id()).matches("[0-9a-f]{32}");
        assertThat(vo.image()).startsWith("data:image/png;base64,");
        assertThat(vo.width()).isEqualTo(GraphCaptchaArt.WIDTH);
        assertThat(vo.height()).isEqualTo(GraphCaptchaArt.HEIGHT);
        assertThat(vo.targets()).hasSize(3);
        assertThat(vo.targets()).extracting(TargetVO::shape)
                .allMatch(shape -> shape.matches("circle|triangle|square|diamond|star|hexagon"));
        assertThat(vo.targets()).extracting(TargetVO::color).allMatch(color -> color.matches("#[0-9A-F]{6}"));
        // 三个目标不重复：图上一共六个图形，每个形状与颜色都是唯一的
        assertThat(vo.targets()).extracting(TargetVO::shape).doesNotHaveDuplicates();
        assertThat(vo.targets()).extracting(TargetVO::color).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("响应里不含任何坐标字段")
    void challengeDoesNotLeakCoordinates() throws Exception {
        String json = objectMapper.writeValueAsString(service.issue());
        assertThat(json).doesNotContain("\"x\"").doesNotContain("\"y\"").doesNotContain("\"radius\"");
    }

    @Test
    @DisplayName("按提示顺序点三个，进度逐个推进并给出票据")
    void pickingInOrderCompletes() {
        ChallengeVO vo = service.issue();
        List<Mark> answer = service.targetsOf(vo.id());
        assertThat(answer).hasSize(3);

        for (int i = 0; i < answer.size(); i++) {
            Mark mark = answer.get(i);
            GraphCaptchaService.Outcome outcome = service.pick(new PickRequest(vo.id(), (double) mark.x(), (double) mark.y()));
            assertThat(outcome.accepted()).as("第 %d 个点对", i + 1).isTrue();
            assertThat(outcome.progress()).isEqualTo(i + 1);
            assertThat(outcome.hitX()).isEqualTo(mark.x());
            assertThat(outcome.hitY()).isEqualTo(mark.y());
            assertThat(outcome.done()).isEqualTo(i == answer.size() - 1);
        }

        // 完成后这张题立刻销毁，不能再点
        GraphCaptchaService.Outcome afterDone =
                service.pick(new PickRequest(vo.id(), (double) answer.get(0).x(), (double) answer.get(0).y()));
        assertThat(afterDone.accepted()).isFalse();
    }

    @Test
    @DisplayName("点空处：判定失败，且这张图立刻作废")
    void missVoidsTheChallenge() {
        ChallengeVO vo = service.issue();
        List<Mark> answer = service.targetsOf(vo.id());

        GraphCaptchaService.Outcome miss = service.pick(new PickRequest(vo.id(), 1.0, 1.0));
        assertThat(miss.accepted()).isFalse();
        assertThat(miss.progress()).isZero();

        // 作废之后再点对的位置也没用 —— 否则等于允许沿着同一张图暴力试坐标
        Mark first = answer.get(0);
        GraphCaptchaService.Outcome retry =
                service.pick(new PickRequest(vo.id(), (double) first.x(), (double) first.y()));
        assertThat(retry.accepted()).isFalse();
        assertThat(service.targetsOf(vo.id())).isEmpty();
    }

    @Test
    @DisplayName("顺序错：先点第二个目标也算点错")
    void outOfOrderIsRejected() {
        ChallengeVO vo = service.issue();
        List<Mark> answer = service.targetsOf(vo.id());

        Mark second = answer.get(1);
        GraphCaptchaService.Outcome outcome = service.pick(new PickRequest(vo.id(), (double) second.x(), (double) second.y()));
        assertThat(outcome.accepted()).isFalse();
    }

    @Test
    @DisplayName("命中容差：点偏到图形边缘外一点仍算中")
    void hitToleranceAllowsSlightOffset() {
        ChallengeVO vo = service.issue();
        Mark first = service.targetsOf(vo.id()).get(0);

        // 半径 42，容差 1.5 倍即 63 —— 偏移 40 仍在范围内
        GraphCaptchaService.Outcome outcome =
                service.pick(new PickRequest(vo.id(), (double) first.x() + 40, (double) first.y()));
        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.progress()).isEqualTo(1);
    }

    @Test
    @DisplayName("不存在的 id 直接判失败")
    void unknownIdIsRejected() {
        GraphCaptchaService.Outcome outcome = service.pick(new PickRequest("0123456789abcdef0123456789abcdef", 300.0, 125.0));
        assertThat(outcome.accepted()).isFalse();
    }
}
