package nerds.studiousTestProject.review.dto.register.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;
import nerds.studiousTestProject.review.entity.Grade;

import java.util.List;

@Builder
@Data
public class RegisterReviewRequest {
    private Long reservationId;
    @NotBlank(message = "청결도 평점은 필수입니다.")
    private Integer cleanliness;
    @NotBlank(message = "방음 평점은 필수입니다.")
    private Integer deafening;
    @NotBlank(message = "비품 상태 평점은 필수입니다.")
    private Integer fixtureStatus;
    @NotBlank(message = "추천 여부는 필수입니다.")
    private Boolean isRecommend;
    @NotBlank(message = "해시태그는 필수입니다.")
    private List<String> hashtags;
    @NotBlank(message = "리뷰는 필수입니다.")
    private String detail;

    public static Grade toGrade(RegisterReviewRequest registerReviewRequest) {
        return Grade.builder()
                .deafening(registerReviewRequest.getDeafening())
                .cleanliness(registerReviewRequest.getCleanliness())
                .fixturesStatus(registerReviewRequest.getFixtureStatus())
                .isRecommended(registerReviewRequest.getIsRecommend())
                .build();
    }
}
