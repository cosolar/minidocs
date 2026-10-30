## 改动说明

<!-- 为什么改、解决什么问题。纯功能罗列留给 diff。 -->

## 关联 issue

<!-- Closes #123 -->

## 类型

- [ ] Bug 修复
- [ ] 新功能
- [ ] 文档
- [ ] 重构 / 性能
- [ ] 其他

## 自检

- [ ] `cd backend && mvn test` 通过
- [ ] `cd front && npm run build` 通过
- [ ] 涉及 UI 的改动附截图或录屏
- [ ] 行为有变化的部分已同步到 `docs/`（设计理由写进 `docs/DESIGN.md`）
- [ ] 权限判定仍只经 `permission/AccessService`，未在业务代码里自行比较角色或 owner
- [ ] 未提交密钥、口令、`.env`、`data/` 或构建产物

## 备注

<!-- 破坏性变更、迁移步骤、需要 review 的重点等 -->
