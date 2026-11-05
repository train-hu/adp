-- 部署包版本支持 git 来源（上传部署包支持填写 git 地址，平台侧 clone → zip）。
-- git_url/git_ref/git_commit：git 来源版本的仓库地址、分支/标签、冻结的 commit SHA（上传文件版本留空）；
-- git_auth_cipher：私有仓库凭证密文（username:password，AES-256-GCM，响应不回传）。
ALTER TABLE package_version ADD COLUMN git_url VARCHAR(512);
ALTER TABLE package_version ADD COLUMN git_ref VARCHAR(255);
ALTER TABLE package_version ADD COLUMN git_commit VARCHAR(40);
ALTER TABLE package_version ADD COLUMN git_auth_cipher BYTEA;
