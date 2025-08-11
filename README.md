# WeekPass
一款高效的burpsuite爆破插件，内置用户名与用户密码字典，一键发送爆破用户名和密码，帮助快速拿下后台权限。

# 使用

在burpsuite中添加该插件，

![image-20250811223316363](https://gaorenyusi.oss-cn-chengdu.aliyuncs.com/img/image-20250811223316363.png)

将登录请求包发送到该插件，

![image-20250811223446811](https://gaorenyusi.oss-cn-chengdu.aliyuncs.com/img/image-20250811223446811.png)

接着对用户名添加$$占位符，对用户密码添加

![image-20250811223533056](https://gaorenyusi.oss-cn-chengdu.aliyuncs.com/img/image-20250811223533056.png)

点击开始爆破，支持按结果长度排序，可查看爆破进度，

![image-20250811223805875](https://gaorenyusi.oss-cn-chengdu.aliyuncs.com/img/image-20250811223805875.png)

支持常见的md5和base64编码形式。

该工具结合作者平时遇见的弱口令情况，旨在快速实现同时对弱密码和用户名进行爆破的作用，可以省去不少麻烦的步骤节省时间。
