package com.ssafy.dockerby.repository.project;

import com.ssafy.dockerby.entity.project.BuildState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BuildStateRepository extends JpaRepository<BuildState,Long> {
  Optional<BuildState> findByProjectId(Long projectId);

  List<BuildState> findAllByProjectIdOrderByBuildNumberAsc(Long projectId);
  List<BuildState> findAllByProjectIdOrderByBuildNumberDesc(Long projectId);
}
