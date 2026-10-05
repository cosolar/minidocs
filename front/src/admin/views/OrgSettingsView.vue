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
import MdIcon from '@/admin/components/MdIcon.vue'
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

const profile = reactive({ name: '', description: '', logoUrl: '', joinPolicy: 'request', discoverable: false })
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
    // 全量提交：后端把 null 当成「不改」，而「清空简介」正是要把值写成空，所以空串显式送出去
    detail.value = await orgApi.update({
      name: profile.name,
      description: profile.description,
      logoUrl: profile.logoUrl,
      joinPolicy: profile.joinPolicy,
      discoverable: profile.discoverable
    })
    ElMessage.success('组织资料已更新')
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
  <div v-loading="loading" style="display: grid; gap: 16px">
    <div class="md-card-panel">
      <div class="md-panel-head">
        <h2>组织资料</h2>
        <span v-if="detail" class="md-ad-chip">{{ detail.myRole }}</span>
      </div>
      <div class="md-panel-body">
        <el-form label-position="top" style="max-width: 560px" :disabled="!canEditProfile">
          <el-form-item label="组织名称">
            <el-input v-model="profile.name" maxlength="64" show-word-limit />
          </el-form-item>
          <el-form-item label="简介">
            <el-input v-model="profile.description" type="textarea" :rows="2" maxlength="255" show-word-limit />
          </el-form-item>
          <el-form-item label="Logo 地址（组织内知识库封面同源，留空即清除）">
            <el-input v-model="profile.logoUrl" maxlength="512" placeholder="/vaults/o1/xx/cover.png 或绝对 URL" />
          </el-form-item>
          <el-form-item label="加入方式">
            <el-radio-group v-model="profile.joinPolicy">
              <el-radio-button value="request">接受申请</el-radio-button>
              <el-radio-button value="invite_only">仅管理员邀请</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="出现在组织发现列表">
            <el-switch v-model="profile.discoverable" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="savingProfile" @click="saveProfile">
              <MdIcon name="save" :size="14" />保存资料
            </el-button>
          </el-form-item>
        </el-form>
        <p v-if="!canEditProfile" class="md-copy-hint">
          组织资料由 OWNER 维护；你在这一档可以改的是成员与自己的库。
        </p>
        <p v-if="detail" class="md-copy-hint">
          标识（slug）{{ detail.slug }} 不参与改名：它是链接与磁盘目录的一部分，换掉等于把所有外链作废。
        </p>
        <p v-if="isPersonal" class="md-copy-hint">
          个人组织随账号自动创建，不能添加成员，也不能删除。
        </p>
      </div>
    </div>

    <div class="md-card-panel">
      <div class="md-panel-head">
        <h2>成员</h2>
        <span class="md-ad-chip">{{ members.length }} 人</span>
      </div>
      <div class="md-panel-body">
        <el-form v-if="canGovern && !isPersonal" inline @submit.prevent="addMember">
          <el-form-item>
            <el-input v-model="newMember.username" placeholder="用户名" style="width: 180px" maxlength="32" />
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
          <el-empty v-if="!members.length" description="暂无成员" :image-size="60" />
          <div v-for="row in members" :key="row.userId" class="md-ad-item">
            <div class="md-ad-item__head">
              <div class="md-ad-item__title">
                <strong>{{ row.displayName || row.username }}</strong>
                <small>@{{ row.username }} · 来自{{ row.joinedFrom }}</small>
              </div>
              <span :class="`md-ad-chip md-ad-chip--${row.role.toLowerCase()}`">{{ row.role }}</span>
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
          <el-table-column label="成员" min-width="180">
            <template #default="{ row }">
              <div>{{ row.displayName || row.username }}</div>
              <div class="md-ad-sub">@{{ row.username }}</div>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="120">
            <template #default="{ row }">
              <span :class="`md-ad-chip md-ad-chip--${row.role.toLowerCase()}`">{{ row.role }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="joinedFrom" label="来源" width="120" />
          <el-table-column label="操作" width="330">
            <template #default="{ row }">
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
            </template>
          </el-table-column>
        </el-table>
        <p class="md-copy-hint">
          OWNER 只有一个且只能靠转让产生；管理员能添加与移出普通成员、处理申请，但任免管理员只能由
          OWNER 操作 —— 按钮显隐读的就是后端给的这一条动作（TENANT_APPOINT_ADMIN）。
        </p>
      </div>
    </div>

    <div v-if="canReviewRequests" class="md-card-panel">
      <div class="md-panel-head">
        <h2>入组申请</h2>
        <span class="md-ad-chip">{{ pending.length }} 条待处理</span>
      </div>
      <div class="md-panel-body">
        <el-empty v-if="!pending.length" description="没有待处理的申请" :image-size="60" />
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
      <div class="md-panel-head"><h2>危险区</h2></div>
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
