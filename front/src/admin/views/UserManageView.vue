<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '@/admin/api'
import MdIcon from '@/admin/components/MdIcon.vue'
import { useIsMobile } from '@/admin/composables/useIsMobile'
import type { AdminUserVO } from '@/shared/api/types'

/** 窄屏用卡片流：操作列有 330px，表格在手机上没法看 */
const isMobile = useIsMobile()

const loading = ref(false)
const list = ref<AdminUserVO[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20, keyword: '', status: '' })

const editVisible = ref(false)
const editForm = reactive({ id: 0, username: '', displayName: '', email: '', role: 'USER' })

const pwdVisible = ref(false)
const pwdForm = reactive({ id: 0, username: '', newPassword: '' })

async function load() {
  loading.value = true
  try {
    const result = await userApi.page({
      page: query.page,
      size: query.size,
      keyword: query.keyword || undefined,
      status: query.status || undefined
    })
    list.value = result.list
    total.value = result.total
  } finally {
    loading.value = false
  }
}

function openEdit(row: AdminUserVO) {
  Object.assign(editForm, {
    id: row.id,
    username: row.username,
    displayName: row.displayName || '',
    email: row.email || '',
    role: row.role
  })
  editVisible.value = true
}

async function submitEdit() {
  await userApi.update(editForm.id, {
    displayName: editForm.displayName,
    email: editForm.email || null,
    role: editForm.role
  })
  editVisible.value = false
  ElMessage.success('已保存')
  await load()
}

function openReset(row: AdminUserVO) {
  Object.assign(pwdForm, { id: row.id, username: row.username, newPassword: '' })
  pwdVisible.value = true
}

async function submitReset() {
  if (pwdForm.newPassword.length < 6) {
    ElMessage.warning('密码长度至少 6 位')
    return
  }
  await userApi.resetPassword(pwdForm.id, pwdForm.newPassword)
  pwdVisible.value = false
  ElMessage.success('密码已重置')
}

async function changeStatus(row: AdminUserVO, action: 'approve' | 'reject' | 'pending' | 'disable' | 'enable') {
  const labels: Record<string, string> = {
    approve: '通过审核', reject: '拒绝', pending: '置回待审核', disable: '禁用', enable: '启用'
  }
  await ElMessageBox.confirm(`确认对「${row.username}」执行「${labels[action]}」？`, '操作确认', { type: 'warning' })
  await userApi.status(row.id, action)
  ElMessage.success('操作成功')
  await load()
}

async function remove(row: AdminUserVO) {
  await ElMessageBox.confirm(
    `删除用户「${row.username}」将同时删除其全部知识库（含磁盘目录）与分享，且不可恢复。`,
    '危险操作',
    { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' }
  )
  await userApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

function statusTag(status: string) {
  if (status === 'active') return 'success'
  if (status === 'pending') return 'warning'
  if (status === 'disabled') return 'danger'
  return 'info'
}

function statusText(status: string) {
  return { active: '正常', pending: '待审核', rejected: '已拒绝', disabled: '已禁用' }[status] || status
}

onMounted(load)
</script>

<template>
  <div class="md-card-panel">
    <div class="md-panel-head">
      <el-input
        v-model="query.keyword"
        placeholder="搜索用户名 / 邮箱 / 显示名"
        clearable
        style="width: 240px"
        @keyup.enter="load"
        @clear="load"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="load">
        <el-option label="正常" value="active" />
        <el-option label="待审核" value="pending" />
        <el-option label="已拒绝" value="rejected" />
        <el-option label="已禁用" value="disabled" />
      </el-select>
      <el-button @click="load">
        <MdIcon name="search" :size="14" />查询
      </el-button>
      <span class="md-spacer" />
      <span class="md-copy-hint">自主注册由 minidocs.register-enabled 控制，默认关闭</span>
    </div>

    <div class="md-panel-body">
      <!-- 窄屏：表格那 330px 的固定操作列会把正文挤没，改成一卡一人 -->
      <div v-if="isMobile" v-loading="loading" class="md-ad-cards">
        <el-empty v-if="!loading && !list.length" description="暂无用户" />
        <div v-for="row in list" :key="row.id" class="md-ad-item">
          <div class="md-ad-item__head">
            <div class="md-ad-item__title">
              <strong>{{ row.username }}</strong>
              <small>{{ row.displayName || '—' }}</small>
            </div>
            <div class="md-ad-item__chips">
              <el-tag size="small" :type="row.role === 'ADMIN' ? 'danger' : 'info'">
                {{ row.role === 'ADMIN' ? '管理员' : '用户' }}
              </el-tag>
              <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
            </div>
          </div>
          <div class="md-ad-item__meta">
            <span>#{{ row.id }}</span>
            <span>{{ row.email || '未填邮箱' }}</span>
            <span>{{ row.kbCount }} 个知识库</span>
            <span>{{ (row.createdAt || '').replace('T', ' ').slice(0, 16) }}</span>
          </div>
          <div class="md-ad-item__actions">
            <el-button size="small" @click="openEdit(row)">
              <MdIcon name="edit" :size="14" />编辑
            </el-button>
            <el-button v-if="row.status === 'pending'" size="small" type="success" @click="changeStatus(row, 'approve')">
              <MdIcon name="check" :size="14" />通过
            </el-button>
            <el-button v-if="row.status === 'pending'" size="small" @click="changeStatus(row, 'reject')">
              <MdIcon name="ban" :size="14" />拒绝
            </el-button>
            <el-button v-if="row.status === 'active' && row.role !== 'ADMIN'" size="small" @click="changeStatus(row, 'disable')">
              <MdIcon name="lock" :size="14" />禁用
            </el-button>
            <el-button v-if="row.status === 'disabled'" size="small" type="success" @click="changeStatus(row, 'enable')">
              <MdIcon name="check" :size="14" />启用
            </el-button>
            <el-dropdown>
              <el-button size="small">
                <MdIcon name="more" :size="14" />更多
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="openReset(row)">
                    <MdIcon name="key" :size="14" />重置密码
                  </el-dropdown-item>
                  <el-dropdown-item @click="changeStatus(row, 'pending')">
                    <MdIcon name="refresh" :size="14" />置回待审核
                  </el-dropdown-item>
                  <el-dropdown-item divided @click="remove(row)">
                    <MdIcon name="trash" :size="14" />删除用户
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
        </div>
      </div>

      <el-table v-else :data="list" v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="用户" min-width="160">
          <template #default="{ row }">
            <div><strong>{{ row.username }}</strong></div>
            <div class="md-copy-hint">{{ row.displayName || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" min-width="180">
          <template #default="{ row }">{{ row.email || '—' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.role === 'ADMIN' ? 'danger' : 'info'">
              {{ row.role === 'ADMIN' ? '管理员' : '用户' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="kbCount" label="知识库" width="90" align="center" />
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">
            <span class="md-copy-hint">{{ (row.createdAt || '').replace('T', ' ').slice(0, 16) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openEdit(row)">
              <MdIcon name="edit" :size="14" />编辑
            </el-button>
            <el-button v-if="row.status === 'pending'" size="small" type="success" @click="changeStatus(row, 'approve')">
              <MdIcon name="check" :size="14" />通过
            </el-button>
            <el-button v-if="row.status === 'pending'" size="small" @click="changeStatus(row, 'reject')">
              <MdIcon name="ban" :size="14" />拒绝
            </el-button>
            <el-button v-if="row.status === 'active' && row.role !== 'ADMIN'" size="small" @click="changeStatus(row, 'disable')">
              <MdIcon name="lock" :size="14" />禁用
            </el-button>
            <el-button v-if="row.status === 'disabled'" size="small" type="success" @click="changeStatus(row, 'enable')">
              <MdIcon name="check" :size="14" />启用
            </el-button>
            <el-dropdown>
              <el-button size="small">
                <MdIcon name="more" :size="14" />更多
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item @click="openReset(row)">
                    <MdIcon name="key" :size="14" />重置密码
                  </el-dropdown-item>
                  <el-dropdown-item @click="changeStatus(row, 'pending')">
                    <MdIcon name="refresh" :size="14" />置回待审核
                  </el-dropdown-item>
                  <el-dropdown-item divided @click="remove(row)">
                    <MdIcon name="trash" :size="14" />删除用户
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
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

    <el-dialog v-model="editVisible" title="编辑用户" width="480px">
      <el-form label-position="top">
        <el-form-item label="用户名">
          <el-input v-model="editForm.username" disabled />
        </el-form-item>
        <el-form-item label="显示名">
          <el-input v-model="editForm.displayName" maxlength="64" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editForm.email" maxlength="128" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="editForm.role">
            <el-radio-button value="USER">普通用户</el-radio-button>
            <el-radio-button value="ADMIN">管理员</el-radio-button>
          </el-radio-group>
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

    <el-dialog v-model="pwdVisible" :title="`重置密码：${pwdForm.username}`" width="440px">
      <el-form label-position="top">
        <el-form-item label="新密码（6-64 位）">
          <el-input v-model="pwdForm.newPassword" show-password maxlength="64" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">
          <MdIcon name="close" :size="14" />取消
        </el-button>
        <el-button type="primary" @click="submitReset">
          <MdIcon name="key" :size="14" />确认重置
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
