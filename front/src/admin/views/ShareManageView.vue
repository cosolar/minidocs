<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { docApi, kbApi, shareApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useIsMobile } from '@/admin/composables/useIsMobile'
import type { DocNode, KbVO, ShareVO } from '@/shared/api/types'

/** 窄屏用卡片流：链接列很宽，表格在手机上只能横向滚 */
const isMobile = useIsMobile()

const loading = ref(false)
const list = ref<ShareVO[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, status: '', scope: '' })

const kbOptions = ref<KbVO[]>([])
const docOptions = ref<{ label: string; value: string }[]>([])

const dialogVisible = ref(false)
const form = reactive({
  kbSlug: undefined as string | undefined,
  scope: 'kb' as 'kb' | 'doc',
  docPath: '',
  encrypted: false,
  password: '',
  expiresIn: 'forever'
})

const editVisible = ref(false)
const editForm = reactive({ token: '', encrypted: false, password: '', expiresIn: 'forever' })

async function load() {
  loading.value = true
  try {
    const result = await shareApi.page({
      page: query.page,
      size: query.size,
      status: query.status || undefined,
      scope: query.scope || undefined
    })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function loadKbOptions() {
  const result = await kbApi.page({ page: 1, size: 100, sort: 'updated' })
  kbOptions.value = result.list
}

async function onKbChange(kbSlug: string) {
  form.docPath = ''
  docOptions.value = []
  if (!kbSlug) return
  const tree = await docApi.tree(kbSlug)
  const flat: { label: string; value: string }[] = []
  const walk = (nodes: DocNode[]) => {
    nodes.forEach((node) => {
      if (node.type === 'doc') flat.push({ label: node.path, value: node.path })
      else walk(node.children || [])
    })
  }
  walk(tree)
  docOptions.value = flat
}

function openCreate() {
  Object.assign(form, { kbSlug: undefined, scope: 'kb', docPath: '', encrypted: false, password: '', expiresIn: 'forever' })
  dialogVisible.value = true
}

async function submitCreate() {
  if (!form.kbSlug) {
    ElMessage.warning('请选择知识库')
    return
  }
  if (form.scope === 'doc' && !form.docPath) {
    ElMessage.warning('请选择要分享的文档')
    return
  }
  if (form.encrypted && !form.password) {
    ElMessage.warning('请设置访问密码')
    return
  }
  const result = await shareApi.create({
    kbSlug: form.kbSlug,
    scope: form.scope,
    docPath: form.scope === 'doc' ? form.docPath : undefined,
    encrypted: form.encrypted,
    password: form.encrypted ? form.password : undefined,
    expiresIn: form.expiresIn
  })
  dialogVisible.value = false
  await load()
  try {
    await navigator.clipboard.writeText(result.url || '')
    ElMessage.success('分享链接已创建并复制')
  } catch {
    ElMessage.success('分享链接已创建')
  }
}

function openEdit(row: ShareVO) {
  editForm.token = row.token
  editForm.encrypted = row.encrypted
  editForm.password = ''
  editForm.expiresIn = row.expiresIn
  editVisible.value = true
}

async function submitEdit() {
  if (editForm.encrypted && !editForm.password) {
    ElMessage.warning('请输入新的访问密码（或关闭密码开关）')
    return
  }
  await shareApi.update(editForm.token, {
    encrypted: editForm.encrypted,
    password: editForm.encrypted ? editForm.password : undefined,
    expiresIn: editForm.expiresIn
  })
  editVisible.value = false
  ElMessage.success('已更新')
  await load()
}

async function revoke(row: ShareVO) {
  await ElMessageBox.confirm('撤销后该链接立即失效，且无法恢复。', '确认撤销分享？', {
    type: 'warning',
    confirmButtonText: '撤销',
    cancelButtonText: '取消'
  })
  await shareApi.revoke(row.token)
  ElMessage.success('已撤销')
  await load()
}

async function copy(url?: string) {
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

function statusTag(status: string) {
  return status === 'active' ? 'success' : status === 'expired' ? 'warning' : 'info'
}

/**
 * 这条分享归不归我改 / 撤销 —— 看后端逐行给的 canGovern（规范 §2.4：撤销权跟着分享创建者走）。
 *
 * <p>字段缺失按「本次没算」放行，与 {@code shared/api/caps} 同一口径：这个列表混着别人建的分享，
 * 把缺失当成 false 等于把治理入口整片藏掉，而后端才是真的闸门。</p>
 */
const governable = (row: ShareVO) => row.canGovern !== false
function statusText(status: string) {
  return { active: '有效', expired: '已过期', revoked: '已撤销', invalid: '已失效' }[status] || status
}

onMounted(async () => {
  await Promise.all([load(), loadKbOptions()])
})
</script>

<template>
  <div class="md-card-panel">
    <div class="md-panel-head">
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="load">
        <el-option label="有效" value="active" />
        <el-option label="已过期" value="expired" />
        <el-option label="已撤销" value="revoked" />
        <el-option label="已失效" value="invalid" />
      </el-select>
      <el-select v-model="query.scope" placeholder="全部范围" clearable style="width: 140px" @change="load">
        <el-option label="整库分享" value="kb" />
        <el-option label="单篇分享" value="doc" />
      </el-select>
      <span class="md-spacer" />
      <el-button type="primary" @click="openCreate">
        <MdIcon name="plus" :size="14" />新建分享
      </el-button>
    </div>

    <div class="md-panel-body">
      <!-- 窄屏：链接是一整串 URL，塞进表格列里只会被截断，卡片里可以整行折开 -->
      <div v-if="isMobile" v-loading="loading" class="md-ad-cards">
        <el-empty v-if="!loading && !list.length" description="暂无分享" />
        <div v-for="row in list" :key="row.token" class="md-ad-item">
          <div class="md-ad-item__head">
            <div class="md-ad-item__title">
              <strong>{{ row.kbName || '知识库' }}</strong>
              <small>{{ row.scope === 'kb' ? '整库分享' : row.docPath }}</small>
            </div>
            <div class="md-ad-item__chips">
              <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
              <el-tag size="small" :type="row.encrypted ? 'warning' : 'info'">
                {{ row.encrypted ? '有密码' : '无密码' }}
              </el-tag>
            </div>
          </div>
          <div class="md-ad-item__meta">
            <span>{{ row.expiresAt ? row.expiresAt.replace('T', ' ').slice(0, 16) : '永久有效' }}</span>
            <span>PV {{ row.views }} · UV {{ row.uv }}</span>
          </div>
          <span class="md-share-url">{{ row.url }}</span>
          <div class="md-ad-item__actions">
            <el-button size="small" @click="copy(row.url)">
              <MdIcon name="copy" :size="14" />复制
            </el-button>
            <el-button v-if="governable(row)" size="small" @click="openEdit(row)">
              <MdIcon name="settings" :size="14" />设置
            </el-button>
            <el-button
              v-if="governable(row)"
              size="small"
              type="danger"
              plain
              :disabled="row.status === 'revoked'"
              @click="revoke(row)"
            >
              <MdIcon name="trash" :size="14" />撤销
            </el-button>
            <span v-if="!governable(row)" class="md-copy-hint">仅创建者可管理</span>
          </div>
        </div>
      </div>

      <el-table v-else :data="list" v-loading="loading" style="width: 100%">
        <el-table-column label="目标" min-width="200">
          <template #default="{ row }">
            <div><strong>{{ row.kbName || '知识库' }}</strong></div>
            <div class="md-copy-hint">{{ row.scope === 'kb' ? '整库分享' : row.docPath }}</div>
          </template>
        </el-table-column>
        <el-table-column label="链接" min-width="260">
          <template #default="{ row }">
            <span class="md-share-url">{{ row.url }}</span>
          </template>
        </el-table-column>
        <el-table-column label="密码" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.encrypted ? 'warning' : 'info'">{{ row.encrypted ? '有' : '无' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="有效期" width="150">
          <template #default="{ row }">
            <span class="md-copy-hint">{{ row.expiresAt ? row.expiresAt.replace('T', ' ').slice(0, 16) : '永久有效' }}</span>
          </template>
        </el-table-column>
        <el-table-column width="118" align="center">
          <!-- 两个数字口径不同，列头说不清就会被当成同一个东西：PV 按访问会话，UV 按天去重 -->
          <template #header>
            <el-tooltip placement="top" :show-after="200">
              <template #default>
                <span class="md-ad-hintable">PV / UV</span>
              </template>
              <template #content>
                PV：访问次数，30 分钟内同一访客只算一次<br>
                UV：独立访客，同一访客每天只算一次
              </template>
            </el-tooltip>
          </template>
          <template #default="{ row }">{{ row.views }} / {{ row.uv }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="270" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="copy(row.url)">
              <MdIcon name="copy" :size="14" />复制
            </el-button>
            <el-button v-if="governable(row)" size="small" @click="openEdit(row)">
              <MdIcon name="settings" :size="14" />设置
            </el-button>
            <el-button
              v-if="governable(row)"
              size="small"
              type="danger"
              plain
              :disabled="row.status === 'revoked'"
              @click="revoke(row)"
            >
              <MdIcon name="trash" :size="14" />撤销
            </el-button>
            <span v-if="!governable(row)" class="md-copy-hint">仅创建者可管理</span>
          </template>
        </el-table-column>
      </el-table>

      <div style="display: flex; justify-content: flex-end; margin-top: 16px">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="load"
          @size-change="load"
        />
      </div>
    </div>

    <el-dialog v-model="dialogVisible" title="新建分享" width="520px">
      <el-form label-position="top">
        <el-form-item label="知识库" required>
          <el-select v-model="form.kbSlug" placeholder="选择知识库" class="md-fill" filterable @change="onKbChange">
            <el-option v-for="kb in kbOptions" :key="kb.slug" :label="kb.name" :value="kb.slug" />
          </el-select>
        </el-form-item>
        <el-form-item label="分享范围">
          <el-radio-group v-model="form.scope">
            <el-radio-button value="kb">整库</el-radio-button>
            <el-radio-button value="doc">单篇</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scope === 'doc'" label="文档" required>
          <el-select v-model="form.docPath" placeholder="选择文档" class="md-fill" filterable>
            <el-option v-for="doc in docOptions" :key="doc.value" :label="doc.label" :value="doc.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="访问密码">
          <el-switch v-model="form.encrypted" />
        </el-form-item>
        <el-form-item v-if="form.encrypted" label="密码">
          <el-input v-model="form.password" show-password maxlength="64" placeholder="4-64 位" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="form.expiresIn" class="md-fill">
            <el-option label="永久有效" value="forever" />
            <el-option label="1 天" value="1d" />
            <el-option label="7 天" value="7d" />
            <el-option label="30 天" value="30d" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">
          <MdIcon name="close" :size="14" />取消
        </el-button>
        <el-button type="primary" @click="submitCreate">
          <MdIcon name="check" :size="14" />创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="分享设置" width="480px">
      <el-form label-position="top">
        <el-form-item label="访问密码">
          <el-switch v-model="editForm.encrypted" />
        </el-form-item>
        <el-form-item v-if="editForm.encrypted" label="新密码">
          <el-input v-model="editForm.password" show-password maxlength="64" placeholder="4-64 位" />
        </el-form-item>
        <el-form-item label="有效期">
          <el-select v-model="editForm.expiresIn" class="md-fill">
            <el-option label="永久有效" value="forever" />
            <el-option label="1 天" value="1d" />
            <el-option label="7 天" value="7d" />
            <el-option label="30 天" value="30d" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">
          <MdIcon name="close" :size="14" />取消
        </el-button>
        <el-button type="primary" @click="submitEdit">
          <MdIcon name="check" :size="14" />保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
