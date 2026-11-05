package com.ops.deploy.project;

/**
 * 子服务对外视图。
 */
public class ProjectSubServiceVO {

    private Long id;
    private String name;
    /** 路径 slug（NULL = 默认服务，沿用旧 workdir/unit）。 */
    private String pathKey;
    /** 服务级启动命令（PROCESS 模式优先于 project 默认；null = 走 project 默认/识别值）。 */
    private String startCmd;
    /** 服务级端口（部署/compose 宿主机映射优先；null = 走请求端口/project 默认）。 */
    private Integer port;
    /** 是否为项目默认子服务（pathKey 为 null/空）。 */
    private boolean defaultService;

    public static ProjectSubServiceVO from(ProjectSubServiceEntity e) {
        ProjectSubServiceVO vo = new ProjectSubServiceVO();
        vo.id = e.getId();
        vo.name = e.getName();
        vo.pathKey = e.getPathKey();
        vo.startCmd = e.getStartCmd();
        vo.port = e.getPort();
        vo.defaultService = e.isDefault();
        return vo;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPathKey() { return pathKey; }
    public void setPathKey(String pathKey) { this.pathKey = pathKey; }
    public String getStartCmd() { return startCmd; }
    public void setStartCmd(String startCmd) { this.startCmd = startCmd; }
    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }
    public boolean isDefaultService() { return defaultService; }
    public void setDefaultService(boolean defaultService) { this.defaultService = defaultService; }
}
