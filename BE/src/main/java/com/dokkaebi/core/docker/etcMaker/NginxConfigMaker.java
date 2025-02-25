package com.dokkaebi.core.docker.etcMaker;

import com.dokkaebi.core.docker.vo.nginx.NginxConfig;
import com.dokkaebi.core.docker.vo.nginx.NginxHttpsOption;
import com.dokkaebi.core.docker.vo.nginx.NginxProxyLocation;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class NginxConfigMaker {

  public String defaultConfig(NginxConfig config) {
    log.info("defaultConfig Start");
    StringBuilder sb = new StringBuilder();
    sb.append(serverTagStart())
        .append(http())
        .append(serverName(config.getDomains()))
        .append(index())
        .append(defaultLocation());

    sb.append(clientMaxBodySize(config.getMaxBodySize()));
    sb.append(addLocations(config.getLocations()));

    sb.append(serverTagEnd());
    return sb.toString();
  }

  public String httpsConfig(NginxConfig config) {
    log.info("httpsConfig Start");
    StringBuilder sb = new StringBuilder();
    sb.append(serverTagStart())
        .append(https(config.getNginxHttpsOption()))
        .append(serverName(config.getDomains()))
        .append(index())
        .append(defaultLocation());

    sb.append(clientMaxBodySize(config.getMaxBodySize()));
    sb.append(addLocations(config.getLocations()));

    sb.append(serverTagEnd());

    sb.append(serverTagStart())
        .append(http())
        .append(serverName(config.getDomains()))
        .append(httpMoved())
        .append(serverTagEnd());
    return sb.toString();
  }

  private String http() {
    log.info("http Start");
    StringBuilder sb = new StringBuilder();
    sb.append("    listen 80;\n")
        .append("    listen [::]:80;\n");
    return sb.toString();
  }

  private String https(NginxHttpsOption option) {
    log.info("https Start");
    StringBuilder sb = new StringBuilder();
    sb.append("    listen 443 ssl;\n")
        .append("    listen [::]:443 ssl;\n")
        .append('\n')
        .append("    ssl_certificate ").append(option.getSslCertificate()).append(";\n")
        .append("    ssl_certificate_key ").append(option.getSslCertificateKey()).append(";\n");
    return sb.toString();
  }

  private String clientMaxBodySize(int size) {
    log.info("clientMaxBodySize Start");
    return "    client_max_body_size " + size + "M;\n";
  }

  private String index() {
    log.info("index Start");
    return "    index index.html index.htm index.nginx-debian.html;\n";
  }

  private String addLocations(List<NginxProxyLocation> locations) {
    log.info("addLocations Start");
    StringBuilder sb = new StringBuilder();
    for (NginxProxyLocation location : locations) {
      if (!location.checkEmpty()) {
        sb.append(addLocation(location));
      }
    }
    return sb.toString();
  }

  private String defaultLocation() {
    log.info("defaultLocation Start");
    StringBuilder sb = new StringBuilder();
    sb.append("    location / {\n")
        .append("        error_page 405 =200 $uri;\n")
        .append("        root /usr/share/nginx/html;\n")
        .append("        try_files $uri $uri/ /index.html;\n")
        .append("    }\n");
    return sb.toString();
  }

  private String addLocation(NginxProxyLocation location) {
    log.info("addLocation Start");
    StringBuilder sb = new StringBuilder();
    sb.append("    location ")
        .append(location.getLocation()).append(" {\n")
        .append("        proxy_pass ")
        .append(location.getUrl()).append(";\n")
        .append("        proxy_http_version 1.1;\n")
        .append("        proxy_set_header Connection \"\";\n")
        .append("\n")
        .append("        proxy_set_header Host $host;\n")
        .append("        proxy_set_header X-Real-IP $remote_addr;\n")
        .append("        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n")
        .append("        proxy_set_header X-Forwarded-Proto $scheme;\n")
        .append("        proxy_set_header X-Forwarded-Host $host;\n")
        .append("        proxy_set_header X-Forwarded-Port $server_port;\n")
        .append("\n")
        .append("        proxy_read_timeout 300;\n")
        .append("    }\n");
    return sb.toString();
  }

  /**
   * https를 사용할 때 80번 포트의 요청은 443 요청으로 HTTP 301
   */
  private String httpMoved() {
    log.info("httpMoved Start");
    StringBuilder sb = new StringBuilder();
    sb.append("    return       301 https://$server_name$request_uri;\n");
    return sb.toString();
  }

  private String serverTagStart() {
    log.info("serverTagStart Start");
    return "server {\n";
  }

  private String serverTagEnd() {
    log.info("serverTagEnd Start");
    return "}\n";
  }

  private String serverName(List<String> domains) {
    log.info("serverName Start");
    StringBuilder sb = new StringBuilder();
    sb.append("    server_name ");
    domains.forEach(domain -> sb.append(domain).append(' '));
    sb.deleteCharAt(sb.length() - 1);
    sb.append(";\n");
    return sb.toString();
  }
}
