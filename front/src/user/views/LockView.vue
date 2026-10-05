<script setup lang="ts">
import { ref } from 'vue'
import Icon from '@/user/components/Icon.vue'

defineProps<{
  kbName?: string
  error?: string
  submitting?: boolean
}>()

const emit = defineEmits<{ (e: 'submit', password: string): void }>()

const password = ref('')

function submit() {
  if (!password.value) return
  emit('submit', password.value)
}

/** 口令错误后由父组件清空输入框，避免用户以为没点击 */
function reset() {
  password.value = ''
}

defineExpose({ reset })
</script>

<template>
  <div class="md-state">
    <main class="md-lock">
      <div class="md-lock__icon" aria-hidden="true">
        <Icon name="lock" :size="30" />
      </div>
      <h1>此知识库已加密</h1>
      <p v-if="kbName">知识库「{{ kbName }}」需要访问密码</p>
      <p v-else>请输入访问密码后继续阅读</p>

      <form class="md-lock__form" @submit.prevent="submit">
        <input
          v-model="password"
          type="password"
          placeholder="请输入访问密码"
          required
          autofocus
          autocomplete="off"
        >
        <button type="submit" class="md-btn md-btn--primary" :disabled="submitting">
          <Icon name="login" :size="14" />{{ submitting ? '校验中…' : '进入阅读' }}
        </button>
      </form>
      <p v-if="error" class="md-lock__error">{{ error }}</p>
    </main>
  </div>
</template>
