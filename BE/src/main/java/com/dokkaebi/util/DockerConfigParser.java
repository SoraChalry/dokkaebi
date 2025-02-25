package com.dokkaebi.util;

import com.dokkaebi.core.docker.vo.docker.BuildConfig;
import com.dokkaebi.core.docker.vo.docker.DbConfig;
import com.dokkaebi.core.docker.vo.docker.DokkaebiProperty;
import com.dokkaebi.core.docker.vo.nginx.NginxConfig;
import com.dokkaebi.dto.project.BuildConfigDto;
import com.dokkaebi.dto.project.ConfigProperty;
import com.dokkaebi.dto.project.NginxConfigDto;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerConfigParser {

  private final PathParser pathParser;

  public DbConfig DbConverter(String name, String framework, String dockerVersion,
                              List<DokkaebiProperty> properties, String dumpLocation, String entryPath) {
    log.info("DbConverter Start : framework = {} , dockerVersion = {}", framework, dockerVersion);

    DbConfig dbConfig = new DbConfig(name, framework, dockerVersion, properties, dumpLocation);
    if(!dumpLocation.isBlank()) {
      dbConfig.addProperty(new DokkaebiProperty("volume", dumpLocation, entryPath));
    }
    log.info("DbConverter Done");
    return dbConfig;
  }

  public BuildConfig buildConverter(String name, String framework, String dockerVersion,
                                    List<DokkaebiProperty> properties, String projectDirectory, String buildPath, String type) {
    log.info("buildConverter Start : framework = {} , dockerVersion = {}", framework, dockerVersion);
    return new BuildConfig(name, framework, dockerVersion, properties, projectDirectory, buildPath,
        type);
  }

  public NginxConfig nginxConverter(NginxConfigDto dto) {
    log.info("nginxConverter Start");
    return new NginxConfig(dto.getDomains(), dto.getLocations(), dto.isHttps(),
        dto.getHttpsOption(), 50);
  }

  public List<DokkaebiProperty> dokkaebiProperties(List<ConfigProperty> properties) {
    log.info("dokkaebiProperties Start");
    List<DokkaebiProperty> newProperties = new ArrayList<>();
    for (ConfigProperty property : properties) {
      newProperties.add(new DokkaebiProperty(property.getProperty(), property.getData(),
          property.getData()));
    }
    return newProperties;
  }

  /**
   * django publish 같은 경우 container가 8000으로 고정
   * @param properties
   * @param port
   * @return
   */
  public List<DokkaebiProperty> dokkaebiPropertiesWithDjango(List<ConfigProperty> properties, String port) {
    log.info("dokkaebiProperties Start");
    List<DokkaebiProperty> newProperties = new ArrayList<>();
    for (ConfigProperty property : properties) {
      newProperties.add(new DokkaebiProperty(property.getProperty(), property.getData(),
              port));
    }

    return newProperties;
  }

  //사용안됨
  public List<DokkaebiProperty> dokkaebiProperty(ConfigProperty property) {
    List<DokkaebiProperty> newProperties = new ArrayList<>();
    newProperties.add(new DokkaebiProperty(property.getProperty(), property.getData(),
        property.getData()));
    return newProperties;
  }

  public List<ConfigProperty> configProperties(List<DokkaebiProperty> properties) {
    log.info("configProperties Start");
    List<ConfigProperty> newProperties = new ArrayList<>();
    for (DokkaebiProperty property : properties) {
      if(!"volume".equals(property.getType()))
        newProperties.add(ConfigProperty.of(property.getType(), property.getHost()));
    }
    return newProperties;
  }

  public List<ConfigProperty> configDbProperties(List<DokkaebiProperty> properties) {
    log.info("configProperties Start");
    List<ConfigProperty> newProperties = new ArrayList<>();
    for (DokkaebiProperty property : properties) {
      if(!"volume".equals(property.getType()) && ! property.getType().equals("publish"))
        newProperties.add(ConfigProperty.of(property.getHost(), property.getContainer()));
    }
    return newProperties;
  }

  //사용안됨
  public List<BuildConfig> buildsConverter(List<BuildConfigDto> dtos) {
    List<BuildConfig> configs = new ArrayList<>();
    for (BuildConfigDto dto : dtos) {
      if (dto.getName().isBlank()) {
        continue;
      }
      if (dto.getFrameworkId() == 0) {
        continue;
      }

      configs.add(new BuildConfig(dto.getName(), null, null,
          dokkaebiProperties(dto.getProperties()), dto.getProjectDirectory(),
          dto.getBuildPath(), dto.getType()));

    }
    return configs;
  }
}
