package fr.xephi.authme.service;

import fr.xephi.authme.TestHelper;
import fr.xephi.authme.data.auth.PlayerAuth;
import fr.xephi.authme.datasource.DataSource;
import fr.xephi.authme.events.RestoreSessionEvent;
import fr.xephi.authme.message.MessageKey;
import fr.xephi.authme.settings.properties.PluginSettings;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * Test for {@link SessionService}.
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    private SessionService sessionService;

    @Mock
    private DataSource dataSource;
    @Mock
    private CommonService commonService;
    @Mock
    private BukkitService bukkitService;

    @BeforeAll
    static void initLogger() {
        TestHelper.setupLogger();
    }

    @BeforeEach
    void createSessionService() {
        given(commonService.getProperty(PluginSettings.SESSIONS_ENABLED)).willReturn(true);
        given(commonService.getProperty(PluginSettings.SESSIONS_EXCLUDED_PLAYERS)).willReturn(Set.of());
        sessionService = new SessionService(commonService, bukkitService, dataSource);
    }

    @Test
    void shouldCheckSessionsEnabledSetting() {
        // given
        Player player = mock(Player.class);
        given(commonService.getProperty(PluginSettings.SESSIONS_ENABLED)).willReturn(false);
        sessionService.reload();

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(false));
        verifyNoInteractions(dataSource);
    }

    @Test
    void shouldCheckIfUserHasSession() {
        // given
        String name = "Bobby";
        Player player = mock(Player.class);
        given(player.getName()).willReturn(name);
        given(dataSource.hasSession(name)).willReturn(false);

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(false));
        verify(commonService).getProperty(PluginSettings.SESSIONS_ENABLED);
        verify(commonService).getProperty(PluginSettings.SESSIONS_EXCLUDED_PLAYERS);
        verifyNoMoreInteractions(commonService);
        verify(dataSource, only()).hasSession(name);
    }

    @Test
    void shouldCheckLastLoginDate() {
        // given
        String name = "Bobby";
        String ip = "127.3.12.15";
        Player player = mockPlayerWithNameAndIp(name, ip);
        given(commonService.getProperty(PluginSettings.SESSIONS_TIMEOUT)).willReturn(8);
        given(dataSource.hasSession(name)).willReturn(true);
        PlayerAuth auth = PlayerAuth.builder()
            .name(name)
            .lastLogin(System.currentTimeMillis() - 10 * 60 * 1000)
            .lastIp(ip).build();
        given(dataSource.getAuth(name)).willReturn(auth);

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(false));
        verify(dataSource).setUnlogged(name);
        verify(dataSource).revokeSession(name);
    }

    @Test
    void shouldRefuseSessionForAuthWithNullLastLoginTimestamp() {
        // given
        String name = "Bobby";
        String ip = "127.3.12.15";
        Player player = mockPlayerWithNameAndIp(name, ip);
        given(dataSource.hasSession(name)).willReturn(true);
        PlayerAuth auth = PlayerAuth.builder()
            .name(name)
            .lastLogin(null)
            .lastIp(ip).build();
        given(dataSource.getAuth(name)).willReturn(auth);

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(false));
        verify(dataSource).setUnlogged(name);
        verify(dataSource).revokeSession(name);
    }

    @Test
    void shouldCheckLastLoginIp() {
        // given
        String name = "Bobby";
        String ip = "127.3.12.15";
        Player player = mockPlayerWithNameAndIp(name, ip);
        given(dataSource.hasSession(name)).willReturn(true);
        given(commonService.getProperty(PluginSettings.SESSIONS_TIMEOUT)).willReturn(8);
        PlayerAuth auth = PlayerAuth.builder()
            .name(name)
            .lastLogin(System.currentTimeMillis() - 7 * 60 * 1000)
            .lastIp("8.8.8.8").build();
        given(dataSource.getAuth(name)).willReturn(auth);

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(false));
        verify(commonService).send(player, MessageKey.SESSION_EXPIRED);
        verify(dataSource).setUnlogged(name);
        verify(dataSource).revokeSession(name);
    }

    @Test
    void shouldEmitEventForValidSession() {
        // given
        String name = "Bobby";
        String ip = "127.3.12.15";
        Player player = mockPlayerWithNameAndIp(name, ip);
        given(commonService.getProperty(PluginSettings.SESSIONS_TIMEOUT)).willReturn(8);
        given(dataSource.hasSession(name)).willReturn(true);
        PlayerAuth auth = PlayerAuth.builder()
            .name(name)
            .lastLogin(System.currentTimeMillis() - 7 * 60 * 1000)
            .lastIp(ip).build();
        given(dataSource.getAuth(name)).willReturn(auth);
        RestoreSessionEvent event = spy(new RestoreSessionEvent(player, false));
        given(bukkitService.createAndCallEvent(any(Function.class))).willReturn(event);

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(true));
        verify(commonService).getProperty(PluginSettings.SESSIONS_ENABLED);
        verify(commonService).getProperty(PluginSettings.SESSIONS_TIMEOUT);
        verify(commonService).getProperty(PluginSettings.SESSIONS_EXCLUDED_PLAYERS);
        verifyNoMoreInteractions(commonService);
        verify(dataSource).setUnlogged(name);
        verify(dataSource).revokeSession(name);
        verify(event).isCancelled();
    }

    @Test
    void shouldHandleNullPlayerAuth() {
        // given
        String name = "Bobby";
        Player player = mockPlayerWithNameAndIp(name, "127.3.12.15");
        given(dataSource.hasSession(name)).willReturn(true);
        given(dataSource.getAuth(name)).willReturn(null);

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(false));
        verify(dataSource).setUnlogged(name);
        verify(dataSource).revokeSession(name);
    }

    @Test
    void shouldHandlePlayerAuthWithNullLastIp() {
        // given
        String name = "Charles";
        Player player = mockPlayerWithNameAndIp(name, "144.117.118.145");
        given(dataSource.hasSession(name)).willReturn(true);
        given(commonService.getProperty(PluginSettings.SESSIONS_TIMEOUT)).willReturn(8);
        PlayerAuth auth = PlayerAuth.builder()
            .name(name)
            .lastIp(null)
            .lastLogin(System.currentTimeMillis() - 2).build();
        given(dataSource.getAuth(name)).willReturn(auth);

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(false));
        verify(dataSource).setUnlogged(name);
        verify(dataSource).revokeSession(name);
    }

    @Test
    void shouldNotResumeSessionForExcludedPlayer() {
        // given
        excludePlayers("bobby");
        String name = "Bobby";
        Player player = mock(Player.class);
        given(player.getName()).willReturn(name);
        given(dataSource.hasSession(name)).willReturn(true);

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(false));
        verify(dataSource).hasSession(name);
        verify(dataSource).setUnlogged(name);
        verify(dataSource).revokeSession(name);
        verifyNoMoreInteractions(dataSource);
        verifyNoInteractions(bukkitService);
    }

    @Test
    void shouldResumeSessionForPlayerNotInExclusionList() {
        // given
        excludePlayers("alice");
        String name = "Bobby";
        String ip = "127.3.12.15";
        Player player = mockPlayerWithNameAndIp(name, ip);
        given(commonService.getProperty(PluginSettings.SESSIONS_TIMEOUT)).willReturn(8);
        given(dataSource.hasSession(name)).willReturn(true);
        PlayerAuth auth = PlayerAuth.builder()
            .name(name)
            .lastLogin(System.currentTimeMillis() - 60 * 1000)
            .lastIp(ip).build();
        given(dataSource.getAuth(name)).willReturn(auth);
        given(bukkitService.createAndCallEvent(any(Function.class))).willReturn(new RestoreSessionEvent(player, false));

        // when
        boolean result = sessionService.canResumeSession(player);

        // then
        assertThat(result, equalTo(true));
    }

    @Test
    void shouldNotReportValidSessionForExcludedPlayer() {
        // given
        excludePlayers("bobby");

        // when
        boolean result = sessionService.hasValidSession("Bobby", "127.3.12.15");

        // then
        assertThat(result, equalTo(false));
        verifyNoInteractions(dataSource);
    }

    @Test
    void shouldNotGrantSessionToExcludedPlayer() {
        // given
        excludePlayers("bobby");

        // when
        sessionService.grantSession("Bobby");

        // then
        verifyNoInteractions(dataSource);
    }

    @Test
    void shouldGrantSessionToPlayerNotInExclusionList() {
        // given
        excludePlayers("alice");

        // when
        sessionService.grantSession("bobby");

        // then
        verify(dataSource, only()).grantSession("bobby");
    }

    private void excludePlayers(String... names) {
        given(commonService.getProperty(PluginSettings.SESSIONS_EXCLUDED_PLAYERS)).willReturn(Set.of(names));
        sessionService.reload();
    }

    private static Player mockPlayerWithNameAndIp(String name, String ip) {
        Player player = mock(Player.class);
        given(player.getName()).willReturn(name);
        TestHelper.mockIpAddressToPlayer(player, ip);
        return player;
    }
}
