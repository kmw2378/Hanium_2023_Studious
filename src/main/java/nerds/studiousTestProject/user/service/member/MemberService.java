package nerds.studiousTestProject.user.service.member;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nerds.studiousTestProject.user.entity.member.MemberType;
import nerds.studiousTestProject.user.dto.general.signup.SignUpRequest;
import nerds.studiousTestProject.user.dto.general.token.JwtTokenResponse;
import nerds.studiousTestProject.user.entity.member.Member;
import nerds.studiousTestProject.user.entity.token.LogoutAccessToken;
import nerds.studiousTestProject.user.entity.token.RefreshToken;
import nerds.studiousTestProject.user.exception.message.ExceptionMessage;
import nerds.studiousTestProject.user.exception.model.TokenCheckFailException;
import nerds.studiousTestProject.user.exception.model.UserAuthException;
import nerds.studiousTestProject.user.repository.member.MemberRepository;
import nerds.studiousTestProject.user.service.token.LogoutAccessTokenService;
import nerds.studiousTestProject.user.service.token.RefreshTokenService;
import nerds.studiousTestProject.user.util.JwtTokenProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Optional;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MemberService {
    private final MemberRepository memberRepository;
    private final RefreshTokenService refreshTokenService;
    private final LogoutAccessTokenService logoutAccessTokenService;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    /**
     * 사용자가 입력한 정보를 가지고 MemberRepository에 저장하는 메소드
     * @param signUpRequest 회원 가입 폼에서 입력한 정보
     *                      이 때, MemberType은 프론트에서 이전에 백으로 부터 전달받은 값 (없다면 DEFAULT로 넘겨줄 것)
     */
    @Transactional
    public void register(SignUpRequest signUpRequest) {
        Long providerId = signUpRequest.getProviderId();
        String email = signUpRequest.getEmail();

        if ((providerId != null && memberRepository.existsByProviderId(providerId)) || memberRepository.existsByEmail(email)) {
            throw new UserAuthException(ExceptionMessage.ALREADY_EXIST_USER);
        }

        // 만약, MemberType이 null 인 경우를 프론트에서 처리할지 백에서 처리할지 고민
        // 그냥 백에서 처리하자.
        MemberType type = signUpRequest.getType();
        if (type == null) {
            type = MemberType.DEFAULT;
        }

        String password = signUpRequest.getPassword();
        String encode = passwordEncoder.encode(password);
        Member member = Member.builder()
                .email(email)
                .password(encode)
                .providerId(providerId)
                .name(signUpRequest.getName())
                .nickname(signUpRequest.getNickname())
                .phoneNumber(signUpRequest.getPhoneNumber())
                .birthday(signUpRequest.getBirthday())
                .roles(signUpRequest.getRoles())
                .type(type)
                .createdDate(new Date())
                .resignedDate(null)
                .build();
        log.info("member = {}", member);
        memberRepository.save(member);
    }

    /**
     * 로그인 하는 시점에 토큰을 생성해서 반환하는 메소드 (로그인을 하는 시점에 토큰이 생성된다)
     * @param email 사용자 이메일
     * @param password 사용자 비밀번호
     * @return 발급한 토큰 정보
     */
    @Transactional
    public JwtTokenResponse login(String email, String password) {
        String encode = passwordEncoder.encode(password);
        Optional<Member> memberOptional = memberRepository.findByEmailAndPassword(email, encode);

        if (memberOptional.isEmpty()) {
            throw new UserAuthException(ExceptionMessage.MISMATCH_EMAIL_OR_PASSWORD);
        }

        Member member = memberOptional.get();
        if (!member.getType().equals(MemberType.DEFAULT)) {
            throw new UserAuthException(ExceptionMessage.NOT_DEFAULT_TYPE_USER);
        }

        return jwtTokenProvider.generateToken(member);
    }

    /**
     * 현재 사용자의 토큰을 만료시고 블랙리스트에 저장하는 메소드
     * @param accessToken 사용자의 accessToken
     * @return 현재 사용자의 이메일
     */
    @Transactional
    public String logout(String accessToken) {
        // 소셜 계정 로그아웃 시 이 부분에서 예외 발생
        log.info("accessToken = {}", accessToken);  // 내일 이걸로 확인해보자.
        String resolvedAccessToken = jwtTokenProvider.resolveToken(accessToken);

        String email = jwtTokenProvider.parseToken(resolvedAccessToken);
        log.info("email = {}", email);

        Long remainTime = jwtTokenProvider.getRemainTime(resolvedAccessToken);
        refreshTokenService.deleteByEmail(email);

        logoutAccessTokenService.saveLogoutAccessToken(LogoutAccessToken.from(email, resolvedAccessToken, remainTime));

        // LogoutDB 가 과부화될 가능성 있음
        return email;
    }

    /**
     * 사용자가 만료된 accessToken 과 만료되지 않은 refreshToken을 넘길 때 새로운 accessToken을 만들어 주는 메소드
     * @param refreshToken 사용자로부터 넘겨 받은 refreshToken
     * @return 새로운 accessToken 이 담긴 JwtTokenResponse 객체
     */
    @Transactional
    public JwtTokenResponse reissue(String refreshToken) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            log.info("auth = {}", authentication);
            throw new UserAuthException(ExceptionMessage.NOT_AUTHORIZE_ACCESS);
        }

        String currentEmail = authentication.getName();
        log.info("currentEmail = {}", currentEmail);
        RefreshToken redisRefreshToken = refreshTokenService.findByEmail(currentEmail);
        if (!refreshToken.equals(redisRefreshToken.getRefreshToken())) {
            log.info("refreshToken = {}", refreshToken);
            log.info("redisRefreshToken = {}", redisRefreshToken.getRefreshToken());
            throw new TokenCheckFailException(ExceptionMessage.MISMATCH_TOKEN);
        }

//        Authorization 사용하여 패스워드 가져올 때 PROTECTED 되있으므로 DB에서 사용자 내역을 가져온다.
//        String password = userDetails.getPassword();
//        참고 : https://djunnni.gitbook.io/springboot/2019-11-30
//        Member member = memberRepository.findById(currentEmail).get();
//        String password = passwordEncoder.encode(member.getPassword());

        return reissueToken(refreshToken, authentication);
    }

    public Optional<Member> findByProviderId(Long providerId) {
        return memberRepository.findByProviderId(providerId);
    }

    /**
     * RefreshToken의 유효기간을 확인 후, 토큰을 재발급해주는 메소드
     * @param refreshToken 사용자의 RefreshToken
     * @param authentication 사용자의 인증 정보
     * @return 재발급된 accessToken
     */
    private JwtTokenResponse reissueToken(String refreshToken, Authentication authentication) {
        String reissueAccessToken = jwtTokenProvider.reissueToken(refreshToken, authentication);
        return JwtTokenResponse.from(reissueAccessToken);
    }
}
