<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { tenantApi } from '@/admin/api'
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
const applying = ref('')

const mine = computed(() => new Set(org.tenants.map((tenant) => tenant.slug)))

async function search() {
  loading.value = true
  try {
    rows.value = await tenantApi.discover(keyword.value.trim() || undefined)
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
  <div style="display: grid; gap: 16px">
    <div class="md-card-panel">
      <div class="md-panel-head">
        <h2>发现组织</h2>
        <div class="md-head-row">
          <el-input
            v-model="keyword"
            placeholder="按名称搜索"
            clearable
            style="width: 240px"
            @keyup.enter="search"
            @clear="search"
          />
          <el-button @click="search">
            <MdIcon name="search" :size="14" />搜索
          </el-button>
        </div>
      </div>
      <div class="md-panel-body">
        <p class="md-copy-hint">
          只有勾选了「出现在发现列表」的组织会出现在这里，看到的字段也都是公开字段；
          成员构成与内部知识库一概不展示。加入后你就能看到该组织的内部知识库。
        </p>
        <el-skeleton v-if="loading && !rows.length" :rows="4" animated />
        <p v-else-if="!rows.length" class="md-ad-sub">没有可加入的组织。可以让管理员按用户名邀请你，或从顶栏「新建组织」自己开一个。</p>
        <div v-else class="md-ad-list">
          <div v-for="row in rows" :key="row.slug" class="md-ad-row">
            <div class="md-ad-row__main">
              <div class="md-ad-row__title">
                <span>{{ row.name }}</span>
                <span class="md-ad-sub">/ {{ row.slug }}</span>
                <span v-if="mine.has(row.slug)" class="md-ad-chip md-ad-chip--member">已加入</span>
              </div>
              <div v-if="row.description" class="md-ad-row__desc">{{ row.description }}</div>
              <div class="md-ad-sub">
                {{ row.memberCount }} 位成员 · {{ row.publicKbCount }} 个公开知识库 ·
                {{ row.joinPolicy === 'request' ? '接受申请' : '仅管理员邀请' }}
              </div>
            </div>
            <div class="md-ad-row__actions">
              <el-button v-if="mine.has(row.slug)" text type="primary" @click="enter(row.slug)">
                <MdIcon name="arrow-right" :size="14" />进入
              </el-button>
              <el-button
                v-else-if="row.joinPolicy === 'request'"
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
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
