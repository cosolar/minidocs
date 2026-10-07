/**
 * Markdown 后缀的显示开关。
 *
 * <p>「目录树里显不显示 {@code .md}」是<b>库级</b>配置，写在库根的 {@code .minidocs.json}
 * 里，跟着 Git 走 —— 与 {@code hidden} 分区同一种载体。团队共用一套命名习惯，
 * 不需要每个人自己去改。</p>
 *
 * <p><b>为什么在前端剥而不是后端剥</b>：{@code DocNode.name} 同时是「文件名」，
 * 工作区的重命名对话框就是拿它回填输入框的（{@code KbWorkspaceView.renameNode}）。
 * 后端一旦把后缀去掉，用户点开重命名就会悄悄丢掉扩展名，把文件改成没有 {@code .md}
 * 的名字 —— 而磁盘上还是那个文件，一年后没人说得清它原来叫什么。</p>
 */

/** 去掉末尾的 {@code .md}（大小写不敏感）。不是 Markdown 或本身就是后缀名的， 原样返回。 */
export function stripMdSuffix(name: string): string {
  return name.replace(/\.md$/i, '')
}

/**
 * 按库的偏好算出树上该显示的名字。
 *
 * <p>只对文档生效：目录名不处理（目录本来就没有 {@code .md}，
 * 真有叫什么就显示什么，否则用户会点不到那个目录）。</p>
 *
 * @param showMdSuffix 库的偏好；{@code undefined} 按「显示」处理 —— 后端在列表与门户
 *                     链路上不下发这一项，那些地方不该因为缺字段而把后缀藏掉
 */
export function displayName(
  name: string,
  type: 'dir' | 'doc' | 'image',
  showMdSuffix?: boolean
): string {
  if (type !== 'doc' || showMdSuffix === false) {
    return name
  }
  return stripMdSuffix(name)
}