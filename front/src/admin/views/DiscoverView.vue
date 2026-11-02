<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tenantApi } from '@/admin/api'
import AdminPageHeader from '@/admin/components/AdminPageHeader.vue'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useOrgStore } from '@/admin/stores/org'
import type { DiscoverOrgVO } from '@/shared/api/types'

/**
 * 发现与申请加入组织（规范 §3.4 / F2）。
 *
 * <p>这一页刻意不带组织段：看它的人此刻还不是任何被列出组织的成员，{@code requireOrg()}
 * 在这里必须失败而不是猜一个。因此本页只用 {@code tenantApi}（{@code /api/tenants/**}）
 * 与 {@code POST /api/console/{slug}/join-request} —— 后者是成员门的唯一豁免路径。</p>
 *
 * <p>申请出去之后本地无法知道结果（后端不推消息），所以列表不维护「已申请」状态：
 * 重复申请由后端幂等处理，页面只在按钮上说明「需要管理员审核」。</p>
 */
const router = useRouter()
const org = useOrgStore()

const keyword = ref('')
const loading = ref(false)
const rows = ref<DiscoverOrgVO[]>([])
const total = ref(0)
const applying = ref('')

const mine = computed(() => new Set(org.tenants.map((tenant) => tenant.slug)))

async function search() {
  loading.value = true
  try {
    const result = await tenantApi.discover(keyword.value.trim() || undefined)
    rows.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function apply(row: DiscoverOrgVO) {
  let message = ''
  try {
    const result = await ElMessageBox.prompt(
      `向「${row.name}」的管理员说明你是谁、为什么要加入。`,
      '申请加入',
      { inputPlaceholder: '申请说明（可选，255 字内）', inputType: 'textarea', inputValue: '' }
    )
    message = result.value || ''
  } catch {
    return
  }
  applying.value = row.slug
  try {
    await tenantApi.applyJoin(row.slug, message.trim() || undefined)
    ElMessage.success('申请已提交，等待管理员审核')
  } finally {
    applying.value = ''
  }
}

function enter(slug: string) {
  router.push({ name: 'kbs', params: { org: slug } })
}

onMounted(async () => {
  await org.load().catch(() => undefined)
  await search()
})
</script>

<template>
  <div class="md-page">
    <AdminPageHeader
      eyebrow="个人"
      title="发现组织"
      description="浏览公开组织并申请加入；只有管理员审核通过后，才能看到对方的内部知识库。"
    />

    <div class="md-toolbar">
      <el-input
        v-model="keyword"
        placeholder="按组织名称搜索"
        clearable
        @keyup.enter="search"
        @clear="search"
      />
      <el-button type="primary" @click="search">
        <MdIcon name="search" :size="14" />搜索
      </el-button>
      <span class="md-toolbar__spacer" />
      <span class="md-toolbar__hint">共 {{ total }} 个公开组织</span>
    </div>

    <section class="md-card-panel">
      <div class="md-panel-body">
        <el-skeleton v-if="loading && !rows.length" :rows="4" animated />
        <div v-else-if="!rows.length" class="md-empty">
          <span class="md-empty__icon"><MdIcon name="globe" :size="22" /></span>
          <div class="md-empty__title">没有找到可加入的组织</div>
          <p class="md-empty__desc">
            可以让管理员按用户名邀请你，或从顶栏「新建组织」自己开一个。
          </p>
        </div>
        <div v-else class="md-org-grid">
          <article v-for="row in rows" :key="row.slug" class="md-org-card">
            <div class="md-org-card__head">
              <span class="md-org-card__mark">{{ row.name.slice(0, 1) }}</span>
              <div>
                <div class="md-org-card__name">{{ row.name }}</div>
                <div class="md-org-card__slug">/ {{ row.slug }}</div>
              </div>
            </div>
            <p class="md-org-card__desc">
              {{ row.description || '这个组织还没有填写简介。' }}
            </p>
            <div class="md-org-card__meta">
              <span>{{ row.memberCount }} 位成员</span>
              <span>{{ row.publicKbCount }} 个公开知识库</span>
            </div>
            <div class="md-org-card__foot">
              <span v-if="mine.has(row.slug)" class="md-ad-chip">已加入</span>
              <span v-else class="md-ad-sub">
                {{ row.joinPolicy === 'request' ? '接受申请' : '仅管理员邀请' }}
              </span>
              <el-button v-if="mine.has(row.slug)" size="small" type="primary" @click="enter(row.slug)">
                <MdIcon name="arrow-right" :size="14" />进入
              </el-button>
              <el-button
                v-else-if="row.joinPolicy === 'request'"
                size="small"
                type="primary"
                plain
                :loading="applying === row.slug"
                @click="apply(row)"
              >
                <MdIcon name="user-plus" :size="14" />申请加入
              </el-button>
              <el-tooltip v-else content="该组织只由管理员直接添加成员" placement="top">
                <span class="md-ad-sub">不可申请</span>
              </el-tooltip>
            </div>
          </article>
        </div>
      </div>
    </section>
  </div>
</template>
