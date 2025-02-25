package com.dokkaebi.service.project;

import com.dokkaebi.repository.project.BuildStateRepository;
import com.dokkaebi.repository.project.ConfigHistoryRepository;
import com.dokkaebi.repository.project.ProjectRepository;
import com.dokkaebi.repository.project.SettingConfigRepository;
import com.dokkaebi.repository.user.UserRepository;
import com.dokkaebi.core.docker.DockerAdapter;
import com.dokkaebi.core.docker.EtcConfigMaker;
import com.dokkaebi.core.docker.vo.docker.BuildConfig;
import com.dokkaebi.core.docker.vo.docker.DbConfig;
import com.dokkaebi.core.docker.vo.docker.DokkaebiProperty;
import com.dokkaebi.core.docker.vo.nginx.NginxConfig;
import com.dokkaebi.core.docker.vo.nginx.NginxHttpsOption;
import com.dokkaebi.core.gitlab.GitlabAdapter;
import com.dokkaebi.core.gitlab.dto.GitlabCloneDto;
import com.dokkaebi.core.gitlab.dto.GitlabWebHookDto;
import com.dokkaebi.core.util.CommandInterpreter;
import com.dokkaebi.dto.framework.DbPropertyConfigDto;
import com.dokkaebi.dto.project.BuildConfigDto;
import com.dokkaebi.dto.project.BuildDetailResponseDto;
import com.dokkaebi.dto.project.BuildTotalDetailDto;
import com.dokkaebi.dto.project.BuildTotalResponseDto;
import com.dokkaebi.dto.project.ConfigHistoryListResponseDto;
import com.dokkaebi.dto.project.ConfigProperty;
import com.dokkaebi.dto.project.DBConfigDto;
import com.dokkaebi.dto.project.GitConfigDto;
import com.dokkaebi.dto.project.NginxConfigDto;
import com.dokkaebi.dto.project.ProjectConfigDto;
import com.dokkaebi.dto.project.ProjectListResponseDto;
import com.dokkaebi.dto.user.UserDetailDto;
import com.dokkaebi.entity.ConfigHistory;
import com.dokkaebi.entity.core.SettingConfig;
import com.dokkaebi.entity.core.Version;
import com.dokkaebi.entity.git.GitlabAccessToken;
import com.dokkaebi.entity.git.WebhookHistory;
import com.dokkaebi.entity.project.BuildState;
import com.dokkaebi.entity.project.Project;
import com.dokkaebi.entity.project.enums.BuildType;
import com.dokkaebi.entity.project.enums.StateType;
import com.dokkaebi.entity.user.User;
import com.dokkaebi.service.git.GitlabService;
import com.dokkaebi.util.DockerConfigParser;
import com.dokkaebi.util.FileManager;
import com.dokkaebi.util.PathParser;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import javassist.NotFoundException;
import javax.persistence.EntityManager;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tomcat.util.http.fileupload.FileUtils;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class ProjectServiceImpl implements ProjectService {

    private final EntityManager em;
    private final ProjectRepository projectRepository;
    private final BuildStateRepository buildStateRepository;
    private final SettingConfigRepository settingConfigRepository;
    private final ConfigHistoryRepository configHistoryRepository;
    private final UserRepository userRepository;
    private final GitlabService gitlabService;

    private final PathParser pathParser;

    private final DockerConfigParser dockerConfigParser;

    @Override
    public Optional<Project> findProjectByName(String name) {
        log.info("findProjectByName Start : projectName = {} ", name);
        return projectRepository.findOneByProjectName(name);
    }

    @Override
    public ProjectConfigDto findConfigById(Long projectId) throws NotFoundException, IOException {
        log.info("findConfigById Start : projectID = {} ", projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(
                () -> new NotFoundException(
                    "ProjectServiceImpl.configByProjectName : " + projectId));

        String configPath = pathParser.configPath(project.getProjectName()).toString();
        String repositoryPath = pathParser.repositoryPath(project.getProjectName(),
            project.getGitConfig().getGitProjectId()).toString();
        String dbVolumePath = pathParser.volumePath().append(repositoryPath).toString();

        List<BuildConfig> buildConfigs = new ArrayList<>();
        NginxConfigDto nginxConfig = new NginxConfigDto(new ArrayList<>(), new ArrayList<>(), false,
            new NginxHttpsOption("", "", ""));
        List<DbConfig> dbConfigs = new ArrayList<>();
        GitConfigDto gitConfigDto = GitConfigDto.from(gitlabService.config(projectId)
            .orElseThrow(() -> new NotFoundException("gitlab config not found")));

        File configDirectory = new File(configPath);
        for (String fileName : configDirectory.list()) {
            if ("build".equals(fileName)) {
                buildConfigs = FileManager.loadJsonFileToList(configPath, "build",
                    BuildConfig.class);
            } else if ("db".equals(fileName)) {
                dbConfigs = FileManager.loadJsonFileToList(configPath, "db", DbConfig.class);
            } else if ("nginx".equals(fileName)) {
                nginxConfig = NginxConfigDto.from(
                    FileManager.loadJsonFile(configPath, "nginx", NginxConfig.class));
            }
        }

        List<BuildConfigDto> buildConfigDtos = new ArrayList<>();
        for (BuildConfig buildConfig : buildConfigs) {
            SettingConfig framework = settingConfigRepository.findBySettingConfigName(
                    buildConfig.getFramework())
                .orElseThrow(() -> new NotFoundException("settingConfig not found"));
            Version version = framework.getLanguage()
                .findVersionByDocker(buildConfig.getVersion())
                .orElseThrow(() -> new IllegalArgumentException("Version miss match"));
            buildConfigDtos.add(
                BuildConfigDto.builder()
                    .frameworkId(framework.getId())
                    .name(buildConfig.getName())
                    .projectDirectory(buildConfig.getProjectDirectory())
                    .buildPath(buildConfig.getBuildPath())
                    .version(version.getInputVersion())
                    .type(buildConfig.getType())
                    .properties(dockerConfigParser.configProperties(buildConfig.getProperties()))
                    .build());
        }

        List<DBConfigDto> dbConfigDtos = new ArrayList<>();
        for (DbConfig config : dbConfigs) {
            SettingConfig framework = settingConfigRepository.findBySettingConfigName(
                config.getFramework()).orElseThrow();
            Version version = framework.getLanguage().findVersionByDocker(config.getVersion())
                .orElseThrow(() -> new IllegalArgumentException("dbconfig version miss match"));
            dbConfigDtos.add(
                DBConfigDto.builder()
                    .name(config.getName())
                    .dumpLocation(config.getDumpLocation().replace(dbVolumePath, ""))
                    .frameworkId(framework.getId())
                    .version(version.getInputVersion())
                    .properties(dockerConfigParser.configDbProperties(config.getProperties()))
                    .port(config.returnPort())
                    .build());
        }

        return ProjectConfigDto.of(projectId, project.getProjectName(), buildConfigDtos,
            gitConfigDto,
            nginxConfig, dbConfigDtos);
    }

    @Override
    public Map<Project, String> upsert(ProjectConfigDto projectConfigDto)
        throws NotFoundException, IOException {
        log.info("upsert Start : projectConfigDto = {} ", projectConfigDto.getProjectName());

        Map<Project, String> result = new HashMap<>();
        Project project;
        if (projectConfigDto.getProjectId() != 0) {
            project = projectRepository.findById(projectConfigDto.getProjectId())
                .orElseThrow(() -> new NotFoundException(
                    "upsert user / project not found / id : " + projectConfigDto.getProjectId()));
            //프로젝트 Id가 있을 시 Update
            log.info("upsert : projectUpdate = {} ", project.getProjectName());
            result.put(project, "update");
        } else {
            project = projectRepository.save(Project.from(projectConfigDto));
            //프로젝트 Id가 없을 시 Create
            log.info("upsert : projectCreate = {} ", project.getProjectName());
            result.put(project, "create");
        }

        String projectPath = pathParser.projectPath(projectConfigDto.getProjectName()).toString();
        String logPath = pathParser.logPath(projectConfigDto.getProjectName()).toString();
        String configPath = pathParser.configPath(projectConfigDto.getProjectName()).toString();
        String repositoryPath = pathParser.repositoryPath(projectConfigDto.getProjectName(),
            projectConfigDto.getGitConfig().getGitProjectId()).toString();
        String dbVolumePath = pathParser.volumePath().append(repositoryPath).toString();
        // config, git clone 지우고 다시 저장

        FileUtils.deleteDirectory(new File(configPath));
        FileUtils.deleteDirectory(new File(repositoryPath));

        // 빌드 환경설정 Convert
        List<BuildConfig> buildConfigs = new ArrayList<>();
        for (BuildConfigDto buildConfigDto : projectConfigDto.getBuildConfigs()) {
            SettingConfig framework = settingConfigRepository.findById(
                buildConfigDto.getFrameworkId()).orElseThrow(() -> new NotFoundException(
                "SettingConfig not found / id: " + buildConfigDto.getFrameworkId()));
            Version version = framework.getLanguage()
                .findVersionByInput(buildConfigDto.getVersion())
                .orElseThrow(() -> new IllegalArgumentException(buildConfigDto.getVersion()));

            if (buildConfigDto.getFrameworkId()==5){
                buildConfigs.add(
                        dockerConfigParser.buildConverter(buildConfigDto.getName(),
                                framework.getSettingConfigName(),
                                version.getDockerVersion(),
                                dockerConfigParser.dokkaebiPropertiesWithDjango(buildConfigDto.getProperties(), "8000"),
                                buildConfigDto.getProjectDirectory(), buildConfigDto.getBuildPath(),
                                buildConfigDto.getType()));
            }else {
                buildConfigs.add(
                        dockerConfigParser.buildConverter(buildConfigDto.getName(),
                                framework.getSettingConfigName(),
                                version.getDockerVersion(),
                                dockerConfigParser.dokkaebiProperties(buildConfigDto.getProperties()),
                                buildConfigDto.getProjectDirectory(), buildConfigDto.getBuildPath(),
                                buildConfigDto.getType()));
            }
        }

        // Git cofig upsert
        log.info("GitConfigDto project ID : {}", project.getId());
        GitConfigDto getConfigDto = projectConfigDto.getGitConfig();
        gitlabService.config(project.getId())
            .map(config -> gitlabService.updateConfig(project, getConfigDto))
            .orElseGet(() -> gitlabService.createConfig(project, getConfigDto));

        // git clone
        log.info("upsert : GitClone Start");
        GitlabAccessToken token = gitlabService.token(getConfigDto.getAccessTokenId());

        String cloneCommand = GitlabAdapter.getCloneCommand(
            GitlabCloneDto.of(token.getAccessToken(), getConfigDto.getRepositoryUrl(),
                getConfigDto.getBranchName(), getConfigDto.getGitProjectId()));

        CommandInterpreter.runDestPath(projectPath, logPath, "Clone", 0, cloneCommand);

        DockerAdapter dockerAdapter = new DockerAdapter(repositoryPath,
            projectConfigDto.getProjectName());

        // dockerfile save
        try {
            dockerAdapter.saveDockerfiles(buildConfigs);
            CommandInterpreter.run(projectPath, "Clone", 0, dockerAdapter.createNetwork());
        } catch (Exception e) {
            log.error("docker file not making {} buildConfigs({})", project.getProjectName(),buildConfigs);
        }

        // NGINX config
        NginxConfig nginxConfig = dockerConfigParser.nginxConverter(
            projectConfigDto.getNginxConfig());
        if (!nginxConfig.checkEmpty()) {
            String defaultConfPath = "";
            for (BuildConfig buildConfig : buildConfigs) {
                if (buildConfig.useNginx()) {
                    String defaultPort = "80";
                    defaultConfPath = buildConfig.getProjectDirectory();
                    if (nginxConfig.isHttps()) {
                        defaultPort = "443";
                        String sslPath = nginxConfig.getNginxHttpsOption().getSslPath();
                        buildConfig.addProperty(new DokkaebiProperty("volume", sslPath, sslPath));
                    }
                    for (DokkaebiProperty property : buildConfig.getProperties()) {
                        if ("publish".equals(property.getType())) {
                            property.updateContainer(defaultPort);
                        }
                    }
                    break;
                }
            }

            if (defaultConfPath.isEmpty()) {
                throw new IllegalArgumentException("NGINX ERROR");
            }

            EtcConfigMaker.nginxConfig(
                new StringBuilder(repositoryPath).append("/").append(defaultConfPath).toString(),
                nginxConfig);
            EtcConfigMaker.saveDockerNginxConfig(configPath, nginxConfig);
        }

        // 빌드 환경설정 파일 저장
        FileManager.saveJsonFile(configPath, "build", buildConfigs);

        // DB condig
        List<DbConfig> dbConfigs = new ArrayList<>();
        for (DBConfigDto dbConfigDto : projectConfigDto.getDbConfigs()) {
            if (dbConfigDto.getName().isBlank()) {
                continue;
            }
            if (dbConfigDto.getFrameworkId() == -1) {
                continue;
            }
            if (dbConfigDto.getPort().isBlank()) {
                continue;
            }
            if (dbConfigDto.getVersion().isBlank()) {
                continue;
            }
            SettingConfig framework = settingConfigRepository.findById(
                dbConfigDto.getFrameworkId()).orElseThrow();
            Version version = framework.getLanguage().findVersionByInput(dbConfigDto.getVersion())
                .orElseThrow(() -> new IllegalArgumentException("DB CONFIG VERSION ERROR"));

            List<DokkaebiProperty> list = new ArrayList<>();
            for (ConfigProperty property : dbConfigDto.getProperties()) {
                if (property.checkEmpty()) {
                    continue;
                }
                list.add(new DokkaebiProperty("environment", property.getProperty(),
                    property.getData()));
            }

            String dbConfigPath = pathParser.dokkaebiConfigPath().toString();
            DbPropertyConfigDto dbPropertyConfigDto = FileManager.loadJsonFile(dbConfigPath,
                framework.getOption(), DbPropertyConfigDto.class);

            if (!dbConfigDto.getPort().isBlank()) {
                list.add(
                    new DokkaebiProperty("publish", dbConfigDto.getPort(),
                        dbPropertyConfigDto.getPort()));
            }

            list.add(new DokkaebiProperty("volume",
                pathParser.volumePath().append("/").append(project.getProjectName()).append("/").append(dbConfigDto.getName()).toString(),
                dbPropertyConfigDto.getVolume()));

            if(!dbPropertyConfigDto.getConfigs().isEmpty()) {
                List<String> config = dbPropertyConfigDto.getConfigs();
                list.add(new DokkaebiProperty("volume", config.get(0),config.get(1)));
            }
            dbConfigs.add(
                dockerConfigParser.DbConverter(dbConfigDto.getName(),
                    framework.getSettingConfigName(),
                    version.getDockerVersion(), list,
                    dbVolumePath + dbConfigDto.getDumpLocation(),
                    dbPropertyConfigDto.getInit()));
        }
        if (!dbConfigs.isEmpty()) {
            FileManager.saveJsonFile(configPath, "db", dbConfigs);
        }

        log.info("loadConfigFilesByFileName Done");
        return result;
    }

    private void createBuildState(Project project, GitlabWebHookDto webHookDto) {
        log.info("createBuildState Start : project.getName = {} ", project.getProjectName());

        List<BuildState> buildStates = new ArrayList<>();

        // 첫수 1부터 시작
        Long buildNumber = Long.valueOf(
            (buildStateRepository.findAllByProjectIdOrderByBuildNumberDesc(project.getId()).size()
                / 3)
                + 1);

        BuildState buildState = BuildState.builder()
            .project(project)
            .buildNumber(buildNumber)
            .buildType(BuildType.valueOf("Pull"))
            .stateType(StateType.valueOf("Processing"))
            .build();

        if (webHookDto != null) {
            WebhookHistory webhookHistory = WebhookHistory.of(webHookDto);
            webhookHistory.setBuildState(buildState);
            buildState.setWebhookHistory(webhookHistory);
        }

        buildStateRepository.save(buildState);
        buildStates.add(buildState);

        BuildState buildState1 = BuildState.builder()
            .project(project)
            .buildNumber(buildNumber)
            .buildType(BuildType.valueOf("Build"))
            .stateType(StateType.valueOf("Waiting"))
            .build();

        if (webHookDto != null) {
            WebhookHistory webhookHistory = WebhookHistory.of(webHookDto);
            webhookHistory.setBuildState(buildState1);
            buildState1.setWebhookHistory(webhookHistory);
        }

        buildStateRepository.save(buildState1);
        buildStates.add(buildState1);

        BuildState buildState2 = BuildState.builder()
            .project(project)
            .buildNumber(buildNumber)
            .buildType(BuildType.valueOf("Run"))
            .stateType(StateType.valueOf("Waiting"))
            .build();

        if (webHookDto != null) {
            WebhookHistory webhookHistory = WebhookHistory.of(webHookDto);
            webhookHistory.setBuildState(buildState2);
            buildState2.setWebhookHistory(webhookHistory);
        }

        buildStateRepository.save(buildState2);
        buildStates.add(buildState2);
        log.info("createBuildState Done");
        em.flush();
    }

    @Override
    public boolean projectIsFailed(Long projectId) throws NotFoundException {
        log.info("projectIsFailed Start : projectId = {} ", projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(
                () -> new NotFoundException("ProjectSerivceImpl.projectIsFailed : " + projectId));
        //프로젝트가 실패상태이면 ture 반환
        if ("Failed".equals(project.getStateType().toString())) {
            log.info("projectIsFailed : return true");
            return true;
        } else {
            log.info("projectIsFailed : return false");
            return false;
        }
    }

    @Override
    public void build(Long projectId, GitlabWebHookDto webHookDto)
        throws NotFoundException, IOException {
        log.info("build Start : projectId = {} ", projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new NotFoundException("ProjectSerivceImpl.build : " + projectId));
        //최근 빌드시간 업데이트
        project.updateRecentBuildDate();
        //프로젝트 상태 진행중으로 변경
        project.updateState(StateType.Processing);

        log.info("build : updateState project.getStateType = {} ", project.getStateType());

        createBuildState(project, webHookDto);

        log.info("build Done");
        em.flush();
    }


    @Override
    public void pullStart(Long projectId, GitlabWebHookDto webHookDto)
        throws NotFoundException, IOException {
        log.info("pullStart Start : projectId = {} ", projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new NotFoundException(
                "ProjectServiceImpl.pullStart /Project not found / id: " + projectId));

        List<BuildState> buildStates = buildStateRepository.findTop3ByProjectIdOrderByIdDesc(
            projectId);

        String logPath = pathParser.logPath(project.getProjectName()).toString();
        String repositoryPath = pathParser.repositoryPath(project.getProjectName(),
                project.getGitConfig().getGitProjectId())
            .toString();

        int buildNumber = Integer.parseInt(buildStates.get(0).getBuildNumber().toString());

        //Pull start
        try { // pull 트라이
            if (buildStates.get(2).getWebhookHistory() != null) {
                List<String> commands = new ArrayList<>();
                commands.add(GitlabAdapter.getPullCommand(webHookDto.getDefaultBranch()));
                CommandInterpreter.runDestPath(repositoryPath, logPath, "Pull", buildNumber,
                    commands);
            }
            // pull 완료 build 진행중 update
            buildStates.get(2).updateStateType("Done");
            buildStates.get(1).updateStateType("Processing");

            em.flush();
            log.info("pullStart : Pull Success : {}", buildStates.get(0).toString());
        } catch (Exception e) { // state failed 넣기
            //pullState failed 입력
            buildStates.get(2).updateStateType("Failed");
            project.updateState(StateType.Failed);

            em.flush();
            log.error("pullStart : Pull failed {}", e);
            throw e;
        }
        log.info("pullStart Done");
    }

    @Override
    public void buildStart(Long projectId, GitlabWebHookDto webHookDto)
        throws NotFoundException, IOException {
        log.info("buildStart Start : projectId = {} ", projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new NotFoundException(
                "ProjectServiceImpl.buildStart / Project not found / id: " + projectId));

        String logPath = pathParser.logPath(project.getProjectName()).toString();
        String configPath = pathParser.configPath(project.getProjectName()).toString();
        String repositoryPath = pathParser.repositoryPath(project.getProjectName(),
                project.getGitConfig().getGitProjectId())
            .toString();

        DockerAdapter dockerAdapter = new DockerAdapter(repositoryPath, project.getProjectName());

        List<BuildConfig> buildConfigs = FileManager.loadJsonFileToList(configPath, "build",
            BuildConfig.class);

        List<BuildState> buildStates = buildStateRepository.findTop3ByProjectIdOrderByIdDesc(
            projectId);

        int buildNumber = Math.toIntExact(buildStates.get(0).getBuildNumber());

        try { // Build 트라이
            List<String> buildCommands = dockerAdapter.getBuildCommands(buildConfigs);
            CommandInterpreter.run(logPath, "Build", (buildNumber), buildCommands);

            // state Done 넣기
            buildStates.get(1).updateStateType("Done");
            buildStates.get(0).updateStateType("Processing");

            em.flush();
            log.info("buildStart : Build Success : {}", buildStates.get(1).toString());
        } catch (Exception e) { // state failed 넣기
            //buildState failed 입력
            buildStates.get(1).updateStateType("Failed");
            project.updateState(StateType.Failed);

            em.flush();
            log.error("buildStart : Build Failed {} ", e);
            throw e;
        }
        log.info("buildStart Done");
    }

    @Override
    public void runStart(Long projectId, GitlabWebHookDto webHookDto)
        throws NotFoundException, IOException {
        log.info("runStart Start: projectId = {} ", projectId);

        Project project = projectRepository.findById(projectId)
            .orElseThrow(() -> new NotFoundException(
                "ProjectServiceImpl.runStart / Project not found / id: " + projectId));

        String logPath = pathParser.logPath(project.getProjectName()).toString();
        String configPath = pathParser.configPath(project.getProjectName()).toString();
        String repositoryPath = pathParser.repositoryPath(project.getProjectName(),
                project.getGitConfig().getGitProjectId())
            .toString();

        DockerAdapter dockerAdapter = new DockerAdapter(repositoryPath, project.getProjectName());

        List<BuildConfig> buildConfigs = new ArrayList<>();

        List<DbConfig> dbConfigs = new ArrayList<>();

        File configDirectory = new File(configPath);
        for (String fileName : configDirectory.list()) {
            if ("build".equals(fileName)) {
                buildConfigs = FileManager.loadJsonFileToList(configPath, "build",
                    BuildConfig.class);
            } else if ("db".equals(fileName)) {
                dbConfigs = FileManager.loadJsonFileToList(configPath, "db", DbConfig.class);
            }
        }

        List<BuildState> buildStates = buildStateRepository.findTop3ByProjectIdOrderByIdDesc(
            projectId);

        int buildNumber = Math.toIntExact(buildStates.get(0).getBuildNumber());
        try { // run 트라이
            if (buildNumber != 1) {
                if (!dbConfigs.isEmpty()) {
                    CommandInterpreter.run(logPath, "Remove", buildNumber,
                        dockerAdapter.getRemoveCommands(dbConfigs));
                }
                if (!buildConfigs.isEmpty()) {
                    CommandInterpreter.run(logPath, "Remove", buildNumber,
                        dockerAdapter.getRemoveCommands(buildConfigs));
                }
            }
            List<String> commands = new ArrayList<>();
            if (!dbConfigs.isEmpty()) {
                commands.addAll(dockerAdapter.getRunCommandsWithVersion(dbConfigs));
            }
            if (!buildConfigs.isEmpty()) {
                commands.addAll(dockerAdapter.getRunCommands(buildConfigs));
            }
            CommandInterpreter.run(logPath, "Run", buildNumber, commands);
            // state Done 넣기
            buildStates.get(0).updateStateType("Done");

            em.flush();
            log.info("runStart : Run Success = {} ", buildStates.get(2).toString());
        } catch (Exception e) { // state failed 넣기
            //dockerRunState failed 입력
            buildStates.get(0).updateStateType("Failed");
            project.updateState(StateType.Failed);

            em.flush();
            log.error("runStart : Run Failed {}", e);
            throw e;
        }
        log.info("runStart Done");
    }

    @Override
    public StateType updateProjectDone(Long projectId, String duration) throws NotFoundException {
        log.info("updateProjectDone Start : projectId = {} ", projectId);
        Project project = projectRepository.findById(projectId)
            .orElseThrow(
                () -> new NotFoundException(
                    "ProjectServiceImpl.updateProjectDone / Project not found / id: " + projectId));

        project.updateState(StateType.valueOf("Done"));

        project.updateLastDuration(duration);

        log.info("updateProject Done : projectId = {} ", project.getStateType());
        em.flush();
        return project.getStateType();
    }

    //history 저장
    public void createConfigHistory(HttpServletRequest request, Project project, String msg)
        throws NotFoundException {
        log.info("createConfigHistory Start : projectName = {} ", project.getProjectName());
        //세션 정보 가져오기
        HttpSession session = request.getSession();
        UserDetailDto userDetailDto = (UserDetailDto) session.getAttribute("user");
        log.info("session User Principal : {} ", userDetailDto.getUsername());

        //로그인 유저 탐색
        User user = userRepository.findByPrincipal(userDetailDto.getUsername())
            .orElseThrow(() -> new NotFoundException(
                "ProjectServiceImpl.createConfigHistory : " + userDetailDto.getUsername()));
        log.info("user find username : {} ", user.getName());

        ConfigHistory history = ConfigHistory.builder()
            .user(user)
            .project(project)
            .msg(msg)
            .build();

        log.info("history save {} to {} detail-{}", history.getUser().getName(),
            history.getProject().getProjectName(), history.getMsg());

        configHistoryRepository.save(history);
        log.info("createConfigHistory Done");
    }

    public List<ConfigHistoryListResponseDto> historyList() {
        log.info("historyList Start");
        List<ConfigHistory> configHistories = configHistoryRepository.findAll(
            Sort.by(Sort.Direction.DESC, "registDate"));
        List<ConfigHistoryListResponseDto> resultList = new ArrayList<>();

        for (ConfigHistory configHistory : configHistories) {
            ConfigHistoryListResponseDto configHistoryListDto = ConfigHistoryListResponseDto.from(
                configHistory);
            resultList.add(configHistoryListDto);
        }

        log.info("historyList Done : listSize = {}", resultList.size());
        return resultList;
    }

    @Override
    public List<BuildTotalResponseDto> buildTotal(Long projectId) throws NotFoundException {
        log.info("buildTotal Start");

        //responseDtos initialized
        List<BuildTotalResponseDto> responseDtos = new ArrayList<>();
        List<BuildTotalDetailDto> buildTotalDetailDtos = new ArrayList<>();

        //해당 projectId의 buildState List로 받음
        List<BuildState> buildStates = buildStateRepository.findAllByProjectIdOrderByBuildNumberAsc(
            projectId); // sort따로 지정하기 라스트 보장x

        //입력 시작 로그 출력
        log.info("buildState insert Start  buildStateSize : {}", buildStates.size());

        int counter = 1;

        //각각의 buildState에 대해 추출후 입력
        for (BuildState buildState : buildStates) {

            BuildTotalDetailDto buildTotalDetailDto = BuildTotalDetailDto.builder()
                .buildStateId(buildState.getId())
                .buildNumber(buildState.getBuildNumber())
                .buildType(buildState.getBuildType())
                .stateType(buildState.getStateType())
                .registDate(buildState.getRegistDate())
                .lastModifiedDate(buildState.getLastModifiedDate())
                .build();

            buildTotalDetailDtos.add(buildTotalDetailDto);

            if (counter == 3) { // 3개씩 List 에 담아주기
                buildTotalDetailDtos.sort(
                    Comparator.comparingLong(BuildTotalDetailDto::getBuildStateId));
                BuildTotalResponseDto buildTotalResponseDto = BuildTotalResponseDto.builder()
                    .buildNumber(buildState.getBuildNumber())
                    .registDate(buildTotalDetailDtos.get(0).getRegistDate())
                    .buildTotalDetailDtos(buildTotalDetailDtos)
                    .build();

                //완성된 buildTotalResponseDto를 저장
                responseDtos.add(buildTotalResponseDto);
                counter = 0;
                buildTotalDetailDtos = new ArrayList<>();
            }
            counter++;
        }
        log.info("buildTotal Done : responseSize = {}", responseDtos.size());
        return responseDtos;
    }

    @Override
    public BuildDetailResponseDto buildDetail(Long buildStateId)
        throws NotFoundException {
        log.info("buildDetail Start");

        BuildState buildState = buildStateRepository.findById(buildStateId)
            .orElseThrow(() -> new NotFoundException(
                "ProjectServiceImpl.buildDetail : Not found build state "
                    + buildStateId));

        log.info("buildDetail : receive success {}", buildState.getProject().getProjectName());

        String logPath = pathParser.logPath(buildState.getProject().getProjectName()).toString();

        String fileName =
            (buildState.getBuildType().toString()) + "_"
                + buildState.getBuildNumber();//  상태_빌드 넘버

        StringBuilder consoleLog = new StringBuilder();

        log.info("buildDetail : lodeFile Start");
        try {
            consoleLog.append(FileManager.loadFile(logPath, fileName));
            log.info("buildDetail : lodeFile Success");
        } catch (Exception error) {
            log.error("buildDetail : There is no console file : {}", error);
            consoleLog.append("There is no console file !!");
        }

        BuildDetailResponseDto.GitInfo gitInfo = null;
        if (buildState.getWebhookHistory() != null) {
            gitInfo = BuildDetailResponseDto.GitInfo.builder()
                .username(buildState.getWebhookHistory().getUsername())
                .gitRepositoryUrl(buildState.getWebhookHistory().getGitHttpUrl())
                .gitBranch(buildState.getWebhookHistory().getDefaultBranch())
                .build();
        }

        String stateType = buildState.getStateType().toString();

        BuildDetailResponseDto buildDetailResponseDto = BuildDetailResponseDto.builder()
            .projectName(buildState.getProject().getProjectName())
            .projectId(buildState.getProject().getId())
            .stateType(StateType.valueOf(stateType))
            .buildNumber(buildState.getBuildNumber())
            .registDate(buildState.getRegistDate())
            .gitInfo(gitInfo)
            .consoleLog(consoleLog.toString())
            .build();

        log.info("buildDetail Done");
        return buildDetailResponseDto;
    }

    @Override
    public List<ProjectListResponseDto> projectList() throws IOException {
        log.info("ProjectList Start");
        List<Project> projectList = projectRepository.findAll();

        List<ProjectListResponseDto> resultList = new ArrayList<>();

        for (Project project : projectList) {
            List<Map<String,String>> ports = new ArrayList<>();
            String configPath = pathParser.configPath(project.getProjectName()).toString();

            //build port 추가
            File buildFile = new File(configPath + "/build");
            if (buildFile.exists()) {            //파일 존재 확인
                List<BuildConfig> buildConfigs = FileManager.loadJsonFileToList(configPath, "build",
                    BuildConfig.class);
                for (BuildConfig buildConfig : buildConfigs) {
                    List<DokkaebiProperty> properties = buildConfig.getProperties();
                    for (DokkaebiProperty property : properties) {
                        Map<String, String> port = new HashMap<>();
                        if (property.getType().equals("publish")) {
                            port.put("name", buildConfig.getName());
                            port.put("host", property.getHost());
                            ports.add(port);
                        }
                    }
                }
            }

            //db port 추가
            File dbFile = new File(configPath + "/db");
            if (dbFile.exists()) {            //파일 존재 확인
                List<DbConfig> dbConfigs = FileManager.loadJsonFileToList(configPath, "db",
                    DbConfig.class);
                for (DbConfig dbConfig : dbConfigs) {
                    List<DokkaebiProperty> properties = dbConfig.getProperties();
                    for (DokkaebiProperty property : properties) {
                        Map<String, String> port = new HashMap<>();
                        if (property.getType().equals("publish")) {
                            port.put("name", dbConfig.getName());
                            port.put("host", property.getHost());
                            ports.add(port);
                        }
                    }
                }
            }
            ProjectListResponseDto projectListDto = ProjectListResponseDto.of(project,ports);
            resultList.add(projectListDto);
        }

        log.info("ProjectList Done : ListSize {}", resultList.size());
        return resultList;
    }

    @Override
    public String makeDuration(LocalDateTime start, LocalDateTime end) {
        long time = Duration.between(start, end).getSeconds();
        String duration = "";
        if (time % 60 == 0) {
            duration = String.valueOf(time / 60) + " 분";
        } else if (time < 60) {
            duration = String.valueOf(time % 60) + " 초";
        } else {
            duration = String.valueOf(time / 60) + " 분 " + String.valueOf(time % 60) + " 초";
        }

        return duration;
    }

    @Override
    public void deleteProject(Long projectId) throws NotFoundException, IOException {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(
                () -> new NotFoundException(
                    "ProjectServiceImpl.configByProjectName : " + projectId));
        String projectPath = pathParser.projectPath(project.getProjectName()).toString();
        String volumePath = pathParser.volumePath().append("/").append(project.getProjectName()).toString();
        if(new File(projectPath).exists()) {
            FileUtils.deleteDirectory(new File(projectPath));
        }
        if(new File(volumePath).exists()) {
            FileUtils.deleteDirectory(new File(volumePath));
        }
        projectRepository.deleteById(projectId);
    }

    @Override
    public void deleteContainer(Long projectId) throws NotFoundException, IOException {

        Project project = projectRepository.findById(projectId)
            .orElseThrow(
                () -> new NotFoundException(
                    "ProjectServiceImpl.configByProjectName : " + projectId));

        String configPath = pathParser.configPath(project.getProjectName()).toString();
        String logPath = pathParser.logPath(project.getProjectName()).toString();

        DockerAdapter dockerAdapter = new DockerAdapter(null, project.getProjectName());

        List<BuildConfig> buildConfigs = new ArrayList<>();
        List<DbConfig> dbConfigs = new ArrayList<>();

        File configDirectory = new File(configPath);
        for (String fileName : configDirectory.list()) {
            if ("build".equals(fileName)) {
                buildConfigs = FileManager.loadJsonFileToList(configPath, "build",
                    BuildConfig.class);
                if (!buildConfigs.isEmpty()) {
                    CommandInterpreter.run(logPath, "Remove", 0,
                        dockerAdapter.getRemoveCommands(buildConfigs));
                }
            } else if ("db".equals(fileName)) {
                dbConfigs = FileManager.loadJsonFileToList(configPath, "db", DbConfig.class);
                if (!dbConfigs.isEmpty()) {
                    CommandInterpreter.run(logPath, "Remove", 0,
                        dockerAdapter.getRemoveCommands(dbConfigs));
                }
            }
        }
    }

    @Override
    public void stopContainer(Long projectId) throws IOException, NotFoundException {
        Project project = projectRepository.findById(projectId)
            .orElseThrow(
                () -> new NotFoundException(
                    "ProjectServiceImpl.configByProjectName : " + projectId));

        String configPath = pathParser.configPath(project.getProjectName()).toString();
        String logPath = pathParser.logPath(project.getProjectName()).toString();

        DockerAdapter dockerAdapter = new DockerAdapter(null, project.getProjectName());

        List<BuildConfig> buildConfigs = new ArrayList<>();
        List<DbConfig> dbConfigs = new ArrayList<>();

        File configDirectory = new File(configPath);
        for (String fileName : configDirectory.list()) {
            if ("build".equals(fileName)) {
                buildConfigs = FileManager.loadJsonFileToList(configPath, "build",
                    BuildConfig.class);
                if (!buildConfigs.isEmpty()) {
                    CommandInterpreter.run(logPath, "Stop", 0,
                        dockerAdapter.getStopCommands(buildConfigs));
                }
            } else if ("db".equals(fileName)) {
                dbConfigs = FileManager.loadJsonFileToList(configPath, "db", DbConfig.class);
                if (!dbConfigs.isEmpty()) {
                    CommandInterpreter.run(logPath, "Stop", 0,
                        dockerAdapter.getStopCommands(dbConfigs));
                }
            }
        }
        project.updateState(StateType.Waiting);
    }
}
