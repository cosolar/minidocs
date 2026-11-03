<script setup lang="ts">
/**
 * 组织设置：资料、成员治理、入组申请、危险区（路由 {@code /console/{org}/settings}）。
 *
 * <p>个人账号的设置在 {@code /account}（{@code AccountView}）—— 那是人的属性，不是组织的。
 * 这里刻意分成两页，避免「改了就影响整个组织」和「只影响自己」混在一张表单里。</p>
 *
 * <p>可操作项一律读后端在组织详情上带的动作向量（{@code myPermissions}），不在前端复制档位表
 * （规范 §2.6）：成员门与三轴裁决都在服务层，这里读 {@code myRole} 只用来显示「我是 OWNER」。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orgApi } from '@/admin/api'
import AdminPageHeader from '@/admin/components/AdminPageHeader.vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import HelpTip from '@/shared/components/HelpTip.vue'
import { useIsMobile } from '@/admin/composables/useIsMobile'
import { useOrgStore } from '@/admin/stores/org'
import { useAuthStore } from '@/admin/stores/auth'
import { useRouter } from 'vue-router'
import { can } from '@/shared/api/caps'
import type { JoinRequestVO, KbActionName, OrgMemberVO, OrgVO } from '@/shared/api/types'

/** 窄屏用卡片流：成员表里「操作」是几段链接式按钮，窄屏表格会把它们挤成两行碎字 */
const isMobile = useIsMobile()

const org = useOrgStore()
const auth = useAuthStore()
const router = useRouter()

const detail = ref<OrgVO | null>(null)
const members = ref<OrgMemberVO[]>([])
const requests = ref<JoinRequestVO[]>([])
const loading = ref(true)
const savingProfile = ref(false)
const adding = ref(false)
const reviewing = ref(0)

const profile = reactive({ name: '', slug: '', description: '', logoUrl: '', joinPolicy: 'request', discoverable: false })
const newMember = reactive({ username: '', role: 'MEMBER' as 'ADMIN' | 'MEMBER' })

/** 治理类入口一律看向量：谁能改资料、谁能任免管理员，后端说得很清楚，这里不猜。 */
const canEditProfile = computed(() => can(detail.value?.myPermissions, 'TENANT_RENAME'))
const canGovern = computed(() => can(detail.value?.myPermissions, 'TENANT_MEMBER_MANAGE'))
const canAppointAdmin = computed(() => can(detail.value?.myPermissions, 'TENANT_APPOINT_ADMIN'))
const canReviewRequests = computed(() => can(detail.value?.myPermissions, 'TENANT_JOIN_REVIEW'))
const canTransfer = computed(() => can(detail.value?.myPermissions, 'TENANT_TRANSFER'))
const canDeleteOrg = computed(() => can(detail.value?.myPermissions, 'TENANT_DELETE'))
const isPersonal = computed(() => detail.value?.type === 'PERSONAL')
/** 个人组织恒为「仅邀请」：后端不接受申请，UI 也不该给入口。 */
const openForApplications = computed(
  () => !isPersonal.value && detail.value?.joinPolicy === 'request' && detail.value?.status === 'active'
)
const pending = computed(() => requests.value.filter((item) => item.status === 'pending'))
const meUserId = computed(() => auth.user?.id)

/** 成员表的角色章：后端给的是枚举，界面上换成中文，颜色按治理权从高到低递减 */
function roleLabel(role: string) {
  return role === 'OWNER' ? '拥有者' : role === 'ADMIN' ? '管理员' : '成员'
}
function roleTone(role: string) {
  return role === 'OWNER' ? 'warn' : role === 'ADMIN' ? 'brand' : 'muted'
}

/** 成员身份色：按用户名固定，同一个人在不同列表里颜色一致 */
function tileTone(username: string) {
  let hash = 0
  for (let i = 0; i < username.length; i += 1) hash = (hash * 31 + username.charCodeAt(i)) >>> 0
  return hash % 6
}
/**
 * OWNER 不能直接退出：这里还剩最后一个 OWNER，退出去组织就没有主人了。
 *
 * <p>读的是「我是不是 OWNER」这个事实，不是我的能力 —— 转让权（{@code TENANT_TRANSFER}）
 * 我确实有，但退出这条在后端是按成员行数判的，向量表达不了「还剩几个人」。</p>
 */
const mustTransferFirst = computed(() => detail.value?.myRole === 'OWNER')

async function load() {
  loading.value = true
  try {
    const [info, list] = await Promise.all([orgApi.detail(), orgApi.members()])
    detail.value = info
    members.value = list
    profile.name = info.name
    profile.slug = info.slug
    profile.description = info.description || ''
    profile.logoUrl = info.logoUrl || ''
    profile.joinPolicy = info.joinPolicy
    profile.discoverable = info.discoverable
    // 申请列表只有有审批权的人能读（后端同一条判定）；给别人发请求只会换来一条 403 提示
    requests.value = canReviewRequests.value ? await orgApi.joinRequests() : []
  } finally {
    loading.value = false
  }
}

async function saveProfile() {
  savingProfile.value = true
  try {
    const oldSlug = detail.value?.slug
    /*
     * 全量提交：后端把 null 当成「不改」，而「清空简介」正是要把值写成空，所以空串显式送出去。
     * slug 只有个人组织可改；团队组织这份表单不带它，免得后端按「越界修改」拒绝整次保存。
     */
    const payload: Record<string, unknown> = {
      name: profile.name,
      description: profile.description,
      logoUrl: profile.logoUrl,
      joinPolicy: profile.joinPolicy,
      discoverable: profile.discoverable
    }
    if (isPersonal.value) {
      payload.slug = profile.slug.trim()
    }
    detail.value = await orgApi.update(payload)
    profile.slug = detail.value.slug
    if (oldSlug && detail.value.slug !== oldSlug) {
      // 地址里的组织段就是上下文本身：先换仓库，再把当前页搬到新地址
      await org.load(true)
      org.enter(detail.value.slug)
      void org.rememberLast(detail.value.slug)
      await router.replace(`/console/${encodeURIComponent(detail.value.slug)}/settings`)
      ElMessage.success('组织标识已更新，旧链接会继续可用')
    } else {
      ElMessage.success('组织资料已更新')
    }
  } finally {
    savingProfile.value = false
  }
}

async function addMember() {
  const username = newMember.username.trim().toLowerCase()
  if (!username) {
    ElMessage.warning('请输入用户名')
    return
  }
  adding.value = true
  try {
    await orgApi.addMember(username, newMember.role)
    newMember.username = ''
    await load()
    ElMessage.success('已添加成员')
  } finally {
    adding.value = false
  }
}

async function setRole(member: OrgMemberVO, role: 'ADMIN' | 'MEMBER') {
  await orgApi.setRole(member.userId, role)
  await load()
  ElMessage.success(`${member.displayName || member.username} 现在是 ${role === 'ADMIN' ? '管理员' : '成员'}`)
}

async function removeMember(member: OrgMemberVO) {
  await ElMessageBox.confirm(
    `确定把 ${member.displayName || member.username} 移出组织吗？他在此组织各库维护名单上的授权会同时失效。`,
    '移除成员',
    { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
  )
  await orgApi.removeMember(member.userId)
  await load()
}

async function review(item: JoinRequestVO, approve: boolean) {
  reviewing.value = item.id
  try {
    await orgApi.reviewJoinRequest(item.id, approve)
    await load()
    ElMessage.success(approve ? '已同意加入' : '已拒绝')
  } finally {
    reviewing.value = 0
  }
}

async function transferTo(member: OrgMemberVO) {
  await ElMessageBox.confirm(
    `把 OWNER 转让给 ${member.displayName || member.username}？你会降级为 ADMIN，这一步不能自己撤回来。`,
    '转让组织者',
    { type: 'warning', confirmButtonText: '确认转让', cancelButtonText: '取消' }
  )
  await orgApi.transferOwner(member.userId)
  await load()
  // 落地组织与成员清单都变了，切一次外壳保证下拉与这里一致
  await org.load(true)
}

async function leaveOrg() {
  await ElMessageBox.confirm('退出后你将失去该组织内全部库的访问权。', '退出组织', {
    type: 'warning',
    confirmButtonText: '退出',
    cancelButtonText: '取消'
  })
  await orgApi.leave()
  org.reset()
  await org.load(true)
  router.replace(org.entry ? `/console/${encodeURIComponent(org.entry)}/kbs` : '/')
}

async function deleteOrg() {
  await ElMessageBox.confirm(
    '删除组织会连带删掉其中所有知识库与磁盘目录，不可恢复。组织内还有库时后端会直接拒绝。',
    '删除组织',
    { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  await orgApi.remove()
  org.reset()
  await org.load(true)
  router.replace(org.entry ? `/console/${encodeURIComponent(org.entry)}/kbs` : '/')
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="md-page">
    <AdminPageHeader
      eyebrow="组织管理"
      title="组织设置"
      description="维护组织资料、成员与入组申请。"
    />

    <div class="md-card-panel">
      <div class="md-panel-head">
        <div class="md-panel-head__text">
          <h2>组织资料<HelpTip v-if="detail" :width="360">
            个人组织可以自定义标识（slug）；旧标识会自动保留为别名，已发出的链接继续可用。
            团队组织的标识创建后不可修改 —— 它已经写进邀请与分享地址里了。
          </HelpTip></h2>
          <p class="md-panel-desc">这些信息会出现在组织列表、邀请与知识库归属中。</p>
        </div>
        <span v-if="detail" class="md-ad-chip">{{ detail.myRole }}</span>
      </div>
      <div class="md-panel-body">
        <el-form label-position="top" class="md-form-grid md-form-max" :disabled="!canEditProfile">
          <el-form-item v-if="isPersonal" class="md-form-span-2">
            <template #label>
              组织标识
              <HelpTip text="会出现在管理地址与阅读链接里。只能使用字母、数字、中文、- 或 _；不能与任何现有或历史标识重复。保存后旧地址会自动跳转到新地址。" />
            </template>
            <el-input v-model="profile.slug" maxlength="48" placeholder="例如 cosolar">
              <template #prepend>/console/</template>
            </el-input>
            <p class="md-copy-hint">保存后管理地址变为 /console/{{ profile.slug.trim() || '…' }}。</p>
          </el-form-item>
          <el-form-item label="组织名称">
            <el-input v-model="profile.name" maxlength="64" show-word-limit />
          </el-form-item>
          <el-form-item label="加入方式">
            <el-radio-group v-model="profile.joinPolicy">
              <el-radio-button value="request">接受申请</el-radio-button>
              <el-radio-button value="invite_only">仅管理员邀请</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="简介" class="md-form-span-2">
            <el-input v-model="profile.description" type="textarea" :rows="3" maxlength="255" show-word-limit />
          </el-form-item>
          <el-form-item class="md-form-span-2">
            <template #label>Logo 地址<HelpTip text="组织内知识库的封面与它同源；留空即清除。" /></template>
            <el-input v-model="profile.logoUrl" maxlength="512" placeholder="/vaults/o1/xx/cover.png 或绝对 URL" />
          </el-form-item>
          <el-form-item label="出现在组织发现列表">
            <el-switch v-model="profile.discoverable" />
          </el-form-item>
          <div class="md-form-footer md-form-span-2">
            <el-button type="primary" :loading="savingProfile" @click="saveProfile">
              <MdIcon name="save" :size="14" />保存资料
            </el-button>
            <span v-if="!canEditProfile" class="md-copy-hint">
              组织资料由 OWNER 维护；你在这一档可以改的是成员与自己的库。
            </span>
            <span v-else-if="isPersonal" class="md-copy-hint">
              个人组织随账号自动创建，不能添加成员，也不能删除。
            </span>
          </div>
        </el-form>
      </div>
    </div>

    <div class="md-card-panel md-table-card">
      <div class="md-panel-head">
        <div class="md-panel-head__text">
          <h2>成员<HelpTip :width="360">
            OWNER 只有一个且只能靠转让产生；管理员能添加与移出普通成员、处理申请，但任免管理员只能由
            OWNER 操作 —— 按钮显隐读的就是后端给的这一条动作（TENANT_APPOINT_ADMIN）。
          </HelpTip></h2>
          <p class="md-panel-desc">成员加入后即可按知识库的可见范围访问内容。</p>
        </div>
        <span class="md-ad-chip">{{ members.length }} 人</span>
      </div>
      <div class="md-panel-body">
        <el-form v-if="canGovern && !isPersonal" inline class="md-member-add" @submit.prevent="addMember">
          <el-form-item>
            <el-input v-model="newMember.username" placeholder="输入用户名添加成员" style="width: 220px" maxlength="32" />
          </el-form-item>
          <el-form-item>
            <el-select v-model="newMember.role" style="width: 120px">
              <!-- 管理员档只有现任 OWNER 能给：ADMIN 能加人，但加不出同伴 -->
              <el-option v-if="canAppointAdmin" label="管理员" value="ADMIN" />
              <el-option label="成员" value="MEMBER" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="adding" native-type="submit">
              <MdIcon name="user-plus" :size="14" />添加
            </el-button>
          </el-form-item>
        </el-form>
        <!-- 窄屏：一卡一人，治理动作原样搬进卡片底部 -->
        <div v-if="isMobile" class="md-ad-cards">
          <div v-if="!members.length" class="md-empty">
            <span class="md-empty__icon"><MdIcon name="users" :size="22" /></span>
            <div class="md-empty__title">暂无成员</div>
          </div>
          <div v-for="row in members" :key="row.userId" class="md-ad-item">
            <div class="md-ad-item__head">
              <div class="md-ad-item__title">
                <strong>{{ row.displayName || row.username }}</strong>
                <small>@{{ row.username }} · 来自{{ row.joinedFrom }}</small>
              </div>
              <span class="md-tbl-pill md-tbl-pill--plain" :class="`md-tbl-pill--${roleTone(row.role)}`">{{ roleLabel(row.role) }}</span>
            </div>
            <div class="md-ad-item__actions">
              <span v-if="row.userId === meUserId" class="md-ad-sub">这是你</span>
              <template v-else-if="canGovern">
                <el-button
                  v-if="canAppointAdmin && row.role !== 'OWNER'"
                  size="small"
                  @click="setRole(row, row.role === 'ADMIN' ? 'MEMBER' : 'ADMIN')"
                >
                  <MdIcon name="key" :size="14" />{{ row.role === 'ADMIN' ? '降为成员' : '设为管理员' }}
                </el-button>
                <el-button v-if="canTransfer && row.role !== 'OWNER'" size="small" @click="transferTo(row)">
                  <MdIcon name="arrow-right" :size="14" />转让 OWNER
                </el-button>
                <el-button
                  v-if="row.role !== 'OWNER' && (canAppointAdmin || row.role === 'MEMBER')"
                  size="small"
                  type="danger"
                  plain
                  @click="removeMember(row)"
                >
                  <MdIcon name="trash" :size="14" />移除
                </el-button>
                <span v-if="row.role === 'OWNER' || (!canAppointAdmin && row.role === 'ADMIN')" class="md-ad-sub">
                  仅 OWNER 可改
                </span>
              </template>
              <span v-else class="md-ad-sub">由管理员维护</span>
            </div>
          </div>
        </div>

        <el-table v-else :data="members" style="width: 100%">
          <el-table-column min-width="220">
            <template #header>
              <span class="md-th"><MdIcon name="user" :size="14" />成员</span>
            </template>
            <template #default="{ row }">
              <div class="md-user-cell">
                <span class="md-tile md-tile--sm" :class="`md-tile--t${tileTone(row.username)}`">
                  {{ (row.displayName || row.username).slice(0, 1) }}
                </span>
                <div class="md-user-cell__text">
                  <strong>{{ row.displayName || row.username }}</strong>
                  <small>@{{ row.username }}</small>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column width="120">
            <template #header>
              <span class="md-th"><MdIcon name="key" :size="14" />角色</span>
            </template>
            <template #default="{ row }">
              <span class="md-tbl-pill md-tbl-pill--plain" :class="`md-tbl-pill--${roleTone(row.role)}`">{{ roleLabel(row.role) }}</span>
            </template>
          </el-table-column>
          <el-table-column width="120">
            <template #header>
              <span class="md-th"><MdIcon name="globe" :size="14" />来源</span>
            </template>
            <template #default="{ row }">{{ row.joinedFrom }}</template>
          </el-table-column>
          <el-table-column width="340" align="right">
            <template #header>
              <span class="md-th"><MdIcon name="gear" :size="14" />操作</span>
            </template>
            <template #default="{ row }">
              <div class="md-row-actions">
                <span v-if="row.userId === meUserId" class="md-ad-sub">这是你</span>
                <template v-else-if="canGovern">
                  <!-- ADMIN 只能动普通成员：任免管理员与移出管理员都是 OWNER 的动作 -->
                  <el-button
                    v-if="canAppointAdmin && row.role !== 'OWNER'"
                    link
                    type="primary"
                    @click="setRole(row, row.role === 'ADMIN' ? 'MEMBER' : 'ADMIN')"
                  >
                    <MdIcon name="key" :size="13" />{{ row.role === 'ADMIN' ? '降为成员' : '设为管理员' }}
                  </el-button>
                  <el-button v-if="canTransfer && row.role !== 'OWNER'" link type="primary" @click="transferTo(row)">
                    <MdIcon name="arrow-right" :size="13" />转让 OWNER
                  </el-button>
                  <el-button
                    v-if="row.role !== 'OWNER' && (canAppointAdmin || row.role === 'MEMBER')"
                    link
                    type="danger"
                    @click="removeMember(row)"
                  >
                    <MdIcon name="trash" :size="13" />移除
                  </el-button>
                  <span v-if="row.role === 'OWNER' || (!canAppointAdmin && row.role === 'ADMIN')" class="md-ad-sub">
                    仅 OWNER 可改
                  </span>
                </template>
                <span v-else class="md-ad-sub">由管理员维护</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <div v-if="canReviewRequests" class="md-card-panel">
      <div class="md-panel-head">
        <div class="md-panel-head__text">
          <h2>入组申请</h2>
          <p class="md-panel-desc">同意后对方立即成为普通成员，可以按可见范围访问组织内容。</p>
        </div>
        <span class="md-ad-chip">{{ pending.length }} 条待处理</span>
      </div>
      <div class="md-panel-body">
        <div v-if="!pending.length" class="md-empty">
          <span class="md-empty__icon"><MdIcon name="clock" :size="22" /></span>
          <div class="md-empty__title">没有待处理的申请</div>
          <p class="md-empty__desc">新的入组申请会出现在这里，同意后对方即可加入组织。</p>
        </div>
        <div v-for="item in pending" :key="item.id" class="md-ad-row">
          <div class="md-ad-row__main">
            <div>{{ item.displayName || item.username }}<span class="md-ad-sub"> @{{ item.username }}</span></div>
            <div v-if="item.message" class="md-ad-sub">{{ item.message }}</div>
          </div>
          <div class="md-ad-row__actions">
            <el-button size="small" type="primary" :loading="reviewing === item.id" @click="review(item, true)">
              <MdIcon name="check" :size="14" />同意
            </el-button>
            <el-button size="small" :loading="reviewing === item.id" @click="review(item, false)">
              <MdIcon name="ban" :size="14" />拒绝
            </el-button>
          </div>
        </div>
        <p v-if="!openForApplications" class="md-copy-hint">
          当前加入方式不对外开放，平台用户看不到申请入口；直接添加成员仍然可用。
        </p>
      </div>
    </div>

    <div v-if="detail && !isPersonal" class="md-card-panel md-card-panel--danger">
      <div class="md-panel-head">
        <div class="md-panel-head__text">
          <h2>危险区</h2>
          <p class="md-panel-desc">这些操作会改变成员关系或删除数据，执行前请确认。</p>
        </div>
      </div>
      <div class="md-panel-body">
        <div class="md-ad-row">
          <div class="md-ad-row__main">
            <div>退出组织</div>
            <div class="md-ad-sub">交出你在这里的一切访问权。剩下最后一个 OWNER 时要先转让，个人组织不能退出。</div>
          </div>
          <div class="md-ad-row__actions">
            <el-button :disabled="mustTransferFirst" @click="leaveOrg">
              <MdIcon name="logout" :size="14" />退出
            </el-button>
          </div>
        </div>
        <div class="md-ad-row">
          <div class="md-ad-row__main">
            <div>删除组织</div>
            <div class="md-ad-sub">连带删除组织内所有知识库与磁盘目录，不可恢复。组织内还有库时会被拒绝。</div>
          </div>
          <div class="md-ad-row__actions">
            <el-button type="danger" :disabled="!canDeleteOrg" @click="deleteOrg">
              <MdIcon name="trash" :size="14" />删除
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
