#!/usr/bin/env bash
set -Eeuo pipefail
ENV=/opt/insight-bi/runtime/mysql.env
BACKUP=/opt/insight-bi/runtime/mysql.env.previous
umask 077
echo "配置 Insight BI 系统元数据到 MySQL"
echo "目标: 172.17.0.12:3306 / datasphere"
read -r -p "MySQL 用户名 [root]: " USERNAME
if [ -z "$USERNAME" ]; then USERNAME=root; fi
read -r -s -p "MySQL 密码（不会回显）: " PASSWORD
printf '\n'
if [ -z "$PASSWORD" ]; then echo "密码不能为空";exit 1;fi
if [ -f "$ENV" ]; then cp "$ENV" "$BACKUP";fi
{
 echo 'SPRING_DATASOURCE_URL=jdbc:mysql://172.17.0.12:3306/datasphere?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
 printf 'SPRING_DATASOURCE_USERNAME=%s\n' "$USERNAME"
 printf 'SPRING_DATASOURCE_PASSWORD=%s\n' "$PASSWORD"
 echo 'SPRING_DATASOURCE_DRIVER=com.mysql.cj.jdbc.Driver'
} > "$ENV"
chmod 600 "$ENV"
unset PASSWORD
systemctl restart insight-bi
for i in $(seq 1 14); do
 if curl -fsS --max-time 2 http://127.0.0.1:18110/api/health | grep -q '"ok":true'; then
  echo "配置成功。MySQL 目标库仅自动创建 insight_ 前缀的三张元数据表。"
  exit 0
 fi
 sleep 1
done
echo "新配置未通过启动检测，正在回滚。"
if [ -f "$BACKUP" ]; then mv "$BACKUP" "$ENV";else rm -f "$ENV";fi
systemctl restart insight-bi
echo "请检查数据库账号/权限/连接参数：journalctl -u insight-bi -n 60"
exit 1
