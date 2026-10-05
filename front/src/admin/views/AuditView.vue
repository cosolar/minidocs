<script setup lang="ts">
/**
 * 组织审计流（路由 {@code /console/{org}/audit}，规范 §9 / F9）。
 *
 * <p>只列本组织，跨组织汇总在平台侧。读它的门槛是 {@code AUDIT_READ}（OWNER / ADMIN），
 * 普通成员进来会是 404 —— 与「组织不存在」同形，这是 §2.5 的口径，不是丢了的提示。
 * 因此入口本身在导航里就对普通成员隐藏，页面只处理「已经进来了」的情况。</p>
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { orgApi } from '@/admin/api'
import { useIsMobile } from '@/admin/composables/useIsMobile'
import type { AuditVO, OrgMemberVO } from '@/shared/api/types'

const route = useRoute()
const router = useRouter()

/** 窄屏用卡片流：五列表格里「详情」是自由键值对，横向滚起来基本读不了 */
const isMobile = useIsMobile()

const rows = ref<AuditVO[]>([])
const total = ref(0)
const loading = ref(false)
const failed = ref(false)

/**
 * 可筛选的动作。这份列表跟着后端**实际写过审计**的动作走，不是 KbAction 的全枚举 ——
 * 大部分读动作与写动作根本不留痕（§9：管理员的普通读不留痕），列出来也永远筛不出结果。
 * 超管越权那几行的 action 是被绕过的那个动作本身（detail 里带 bypass=SUPER_ADMIN），
 * 所以它不在列表里：筛它得靠详情，不靠 action。
 */
const actions = [
  { value: 'all', label: '全部动作' },
  { value: 'KB_DELETE', label: '删除知识库' },
  { value: 'KB_SET_VISIBILITY', label: '改可见范围' },
  { value: 'KB_MEMBER_MANAGE', label: '维护名单变更' },
  { value: 'SHARE_CREATE', label: '创建分享' },
  { value: 'SHARE_REVOKE', label: '吊销分享' },
  { value: 'TENANT_MEMBER_MANAGE', label: '组织成员变更' },
  { value: 'TENANT_JOIN_REVIEW', label: '入组申请审批' },
  { value: 'TENANT_TRANSFER', label: '转让 OWNER' },
  { value: 'TENANT_VIEW', label: '退出组织' }
]

/**
 * 地址里的 action 只认列表里有的值：一个认不出的值会筛出空列表，看上去像「没人干过这件事」，
 * 而真实原因是链接里那个词后端从没写过 —— 宁可不筛。
 */
function knownAction(value: unknown): string {
  return typeof value === 'string' && actions.some((item) => item.value === value) ? value : 'all'
}

/**
 * 筛选条件全部留在地址里：审计是拿来「事后对齐同一件事」的，把一条带条件的链接发给同事，
 * 对方看到的必须是同一批条目，而不是回到默认的第一页全量。
 */
const query = reactive({
  page: Math.max(1, Number(route.query.page) || 1),
  size: [20, 50, 100].includes(Number(route.query.size)) ? Number(route.query.size) : 20,
  /** {@code all} 是哨兵值而不是空串：下拉框把空串当成「没有选中」，会显示占位符而不是「全部动作」 */
  action: knownAction(route.query.action),
  actor: Number(route.query.actor) || undefined as number | undefined,
  range: dateRange(route.query.from, route.query.to)
})

const members = ref<OrgMemberVO[]>([])
/** 成员名单读不到就只少一个筛选项，不让审计页整个打不开（§2.5：那条通道另有门槛） */
const memberOptions = computed(() =>
  members.value.map((item) => ({
    value: item.userId,
    label: `${item.displayName || item.username}（${item.role === 'OWNER' ? '拥有者' : item.role === 'ADMIN' ? '管理员' : '成员'}）`
  }))
)

/** 地址里的 from/to 只在两个都是合法 yyyy-MM-dd 时才当成区间，否则宁可不筛 */
function dateRange(from: unknown, to: unknown): [string, string] | null {
  const pattern = /^\d{4}-\d{2}-\d{2}$/
  return typeof from === 'string' && typeof to === 'string' && pattern.test(from) && pattern.test(to)
    ? [from, to]
    : null
}

async function load() {
  loading.value = true
  failed.value = false
  try {
    const result = await orgApi.audit({
      page: query.page,
      size: query.size,
      action: query.action === 'all' ? undefined : query.action,
      actor: query.actor || undefined,
      from: query.range?.[0],
      to: query.range?.[1]
    })
    rows.value = result.list
    total.value = result.total
  } catch {
    failed.value = true
  } finally {
    loading.value = false
  }
}

/** 动作下拉是单向绑定的（默认值来自地址栏），所以选中值要在这里写回，光靠 change 事件不带状态 */
async function pickAction(value: string) {
  query.action = value
  await applyFilters()
}

/** 任何一条筛选变了都回到第一页：留在第 7 页换个条件，看到的是空列表而不是「筛不出东西」 */
async function applyFilters() {
  query.page = 1
  await reload()
}

async function reload() {
  const range = query.range
  await router.replace({
    query: {
      ...(query.page > 1 ? { page: String(query.page) } : {}),
      ...(query.size !== 20 ? { size: String(query.size) } : {}),
      ...(query.action !== 'all' ? { action: query.action } : {}),
      ...(query.actor ? { actor: String(query.actor) } : {}),
      ...(range ? { from: range[0], to: range[1] } : {})
    }
  })
  await load()
}

/** detail 是自由形状（后端按动作给不同键），直接铺开键值对，不猜结构。 */
function detailEntries(detail: AuditVO['detail']): Array<[string, string]> {
  if (!detail || typeof detail !== 'object') {
    return detail ? [['值', String(detail)]] : []
  }
  return Object.entries(detail).map(([key, value]) => [key, value === null ? '—' : String(value)])
}

onMounted(async () => {
  await load()
  try {
    members.value = await orgApi.members()
  } catch {
    members.value = []
  }
})
</script>

<template>
  <div class="md-card-panel">
    <div class="md-panel-head">
      <h2>操作审计</h2>
      <span class="md-spacer" />
      <el-select
        :model-value="query.action"
        style="width: 190px"
        @change="(value: unknown) => pickAction(String(value))"
      >
        <el-option v-for="item in actions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select
        v-model="query.actor"
        style="width: 200px"
        placeholder="全部操作者"
        clearable
        filterable
        @change="applyFilters"
      >
        <el-option v-for="item in memberOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-date-picker
        v-model="query.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        unlink-panels
        range-separator="至"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 240px"
        @change="applyFilters"
      />
      <span class="md-ad-sub">共 {{ total }} 条 · 保留 180 天</span>
    </div>
    <div v-loading="loading" class="md-panel-body">
      <p v-if="failed" class="md-copy-hint">读取失败：要么这个组织不属于你，要么你在这里的角色看不到审计。</p>
      <div v-else-if="isMobile" class="md-ad-cards">
        <el-empty v-if="!rows.length" description="暂无记录" />
        <div v-for="row in rows" :key="row.id" class="md-ad-item">
          <div class="md-ad-item__head">
            <div class="md-ad-item__title">
              <strong>{{ row.action }}</strong>
              <small>{{ row.createdText }}</small>
            </div>
            <span class="md-ad-chip">{{ row.actor || '—' }}</span>
          </div>
          <div class="md-ad-item__meta">
            <span v-if="row.kbName">{{ row.kbName }}<template v-if="row.docPath"> / {{ row.docPath }}</template></span>
            <span v-else-if="row.docPath">{{ row.docPath }}</span>
            <span v-else>—</span>
          </div>
          <div v-if="detailEntries(row.detail).length" class="md-ad-item__chips">
            <span v-for="entry in detailEntries(row.detail)" :key="entry[0]" class="md-ad-detail">
              {{ entry[0] }}={{ entry[1] }}
            </span>
          </div>
        </div>
      </div>
      <el-table v-else :data="rows" style="width: 100%">
        <el-table-column prop="createdText" label="时间" width="150" />
        <el-table-column label="操作者" width="140">
          <template #default="{ row }">{{ row.actor || '—' }}</template>
        </el-table-column>
        <el-table-column label="动作" width="190">
          <template #default="{ row }"><span class="md-ad-chip">{{ row.action }}</span></template>
        </el-table-column>
        <el-table-column label="对象" min-width="220">
          <template #default="{ row }">
            <span v-if="row.kbName">{{ row.kbName }}</span>
            <span v-if="row.docPath" class="md-ad-sub"> / {{ row.docPath }}</span>
            <span v-if="!row.kbName && !row.docPath" class="md-ad-sub">—</span>
          </template>
        </el-table-column>
        <el-table-column label="详情" min-width="240">
          <template #default="{ row }">
            <span v-for="entry in detailEntries(row.detail)" :key="entry[0]" class="md-ad-detail">
              {{ entry[0] }}={{ entry[1] }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <div class="md-ad-pager">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="reload"
          @size-change="applyFilters"
        />
      </div>
    </div>
  </div>
</template>
