<template>
  <view class="chat-page">
    <view class="service-header">
      <view class="service-avatar">M</view>
      <view class="service-intro">
        <text class="service-name">Mall 智能客服</text>
        <text class="service-status">在线为你解答</text>
      </view>
      <button class="new-chat" :disabled="sending" @click="startNewConversation">新对话</button>
    </view>

    <scroll-view v-if="conversations.length" class="conversation-strip" scroll-x>
      <view
        v-for="item in conversations"
        :key="item.id"
        class="conversation-chip"
        :class="{ active: currentConversationId === item.id }"
        @click="selectConversation(item.id)"
      >
        <text class="conversation-preview">{{ item.preview || '历史对话' }}</text>
      </view>
    </scroll-view>

    <scroll-view
      class="message-list"
      scroll-y
      :scroll-into-view="scrollTarget"
      :scroll-with-animation="true"
    >
      <view v-if="!messages.length" class="welcome-card">
        <text class="welcome-title">你好，我是 Mall 智能客服</text>
        <text class="welcome-copy">可以向我描述你遇到的问题，我会尽力帮你解答。</text>
        <text class="welcome-hint">涉及订单或售后时，请不要在对话中发送密码、验证码等敏感信息。</text>
      </view>

      <view
        v-for="(message, index) in messages"
        :key="`${message.role}-${index}`"
        class="message-row"
        :class="message.role"
      >
        <view class="message-bubble">
          <text class="message-content">{{ message.content || (sending && index === messages.length - 1 ? '正在思考…' : '') }}</text>
          <view v-if="message.citations?.length" class="citations">
            <text v-for="(citation, citationIndex) in message.citations" :key="citationIndex" class="citation">
              {{ citation.title || citation.source || `参考资料 ${citationIndex + 1}` }}
            </text>
          </view>
        </view>
      </view>
      <view id="message-bottom" class="message-bottom"></view>
    </scroll-view>

    <view class="composer">
      <textarea
        v-model="draft"
        class="message-input"
        maxlength="2000"
        auto-height
        :disabled="sending"
        placeholder="输入你想咨询的问题"
        confirm-type="send"
        @confirm="sendMessage"
      />
      <button class="send-button" :disabled="sending || !draft.trim()" @click="sendMessage">
        {{ sending ? '回复中' : '发送' }}
      </button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  getCustomerConversationsAPI,
  getCustomerMessagesAPI,
  getCustomerServiceBaseURL,
  type CustomerCitation,
  type CustomerConversation,
  type CustomerMessage,
} from '@/apis/customerService'
import { useMemberStore } from '@/stores/member'

interface DisplayMessage extends CustomerMessage {
  citations?: CustomerCitation[]
}

const memberStore = useMemberStore()
const conversations = ref<CustomerConversation[]>([])
const messages = ref<DisplayMessage[]>([])
const currentConversationId = ref<number | null>(null)
const draft = ref('')
const sending = ref(false)
const scrollTarget = ref('message-bottom')
let activeController: AbortController | null = null

onLoad(() => {
  if (!uni.getStorageSync('token')) {
    uni.redirectTo({ url: '/pages/public/login' })
    return
  }
  void loadConversations(true)
})

onMounted(() => {
  if (typeof window !== 'undefined') {
    window.addEventListener('pagehide', abortActiveRequest)
  }
})

onBeforeUnmount(() => {
  abortActiveRequest()
  if (typeof window !== 'undefined') {
    window.removeEventListener('pagehide', abortActiveRequest)
  }
})

function abortActiveRequest() {
  activeController?.abort()
  activeController = null
}

async function loadConversations(openFirst: boolean) {
  try {
    const result = await getCustomerConversationsAPI()
    conversations.value = result.data.items || []
    if (openFirst && conversations.value.length) {
      await selectConversation(conversations.value[0].id)
    }
  } catch {
    // 新对话仍可创建；历史列表暂时不可用时不阻断客服入口。
    conversations.value = []
  }
}

async function selectConversation(conversationId: number) {
  if (sending.value) return
  currentConversationId.value = conversationId
  try {
    const result = await getCustomerMessagesAPI(conversationId)
    messages.value = result.data.items || []
    scrollToLatest()
  } catch {
    uni.showToast({ title: '历史消息加载失败，请稍后重试', icon: 'none' })
  }
}

function startNewConversation() {
  if (sending.value) return
  currentConversationId.value = null
  messages.value = []
  draft.value = ''
}

function scrollToLatest() {
  scrollTarget.value = ''
  setTimeout(() => {
    scrollTarget.value = 'message-bottom'
  }, 30)
}

function showLogin() {
  memberStore.memberLogout()
  uni.redirectTo({ url: '/pages/public/login' })
}

async function sendMessage() {
  const text = draft.value.trim()
  if (!text || sending.value) return
  if (!uni.getStorageSync('token')) {
    showLogin()
    return
  }

  draft.value = ''
  messages.value.push({ role: 'user', content: text })
  messages.value.push({ role: 'assistant', content: '' })
  const assistantIndex = messages.value.length - 1
  sending.value = true
  scrollToLatest()
  activeController = new AbortController()

  try {
    const response = await fetch(`${getCustomerServiceBaseURL()}/customer-service/chat`, {
      method: 'POST',
      headers: {
        Authorization: uni.getStorageSync('token'),
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
      },
      body: JSON.stringify({ message: text, conversationId: currentConversationId.value }),
      signal: activeController.signal,
    })

    if (response.status === 401) {
      showLogin()
      throw new Error('登录状态已过期，请重新登录')
    }
    if (!response.ok) {
      let detail = ''
      try {
        const body = await response.json()
        detail = body.message || body.data?.message || ''
      } catch {
        // 错误响应不一定是 JSON。
      }
      throw new Error(detail || '客服暂时不可用，请稍后重试')
    }
    if (!response.body) throw new Error('当前浏览器不支持客服流式回复')

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) handleSseFrame(frame, assistantIndex)
      if (done) break
    }
    if (buffer.trim()) handleSseFrame(buffer, assistantIndex)
    if (!messages.value[assistantIndex].content) {
      messages.value[assistantIndex].content = '暂时没有收到回复，请稍后重试。'
    }
    void loadConversations(false)
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') return
    messages.value[assistantIndex].content = error instanceof Error ? error.message : '发送失败，请稍后重试'
  } finally {
    sending.value = false
    activeController = null
    scrollToLatest()
  }
}

function handleSseFrame(frame: string, assistantIndex: number) {
  const lines = frame.split(/\r?\n/)
  const eventName = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
  const data = lines
    .filter(line => line.startsWith('data:'))
    .map(line => line.slice(5).trimStart())
    .join('\n')
  if (!data || data === '[DONE]') return

  let payload: Record<string, unknown>
  try {
    payload = JSON.parse(data) as Record<string, unknown>
  } catch {
    return
  }
  if (eventName === 'error') {
    throw new Error(typeof payload.message === 'string' ? payload.message : '客服暂时不可用，请稍后重试')
  }
  if (typeof payload.delta === 'string') {
    messages.value[assistantIndex].content += payload.delta
  }
  if (payload.event === 'done' && typeof payload.conversation_id === 'number') {
    currentConversationId.value = payload.conversation_id
  }
  if (payload.event === 'citations' && Array.isArray(payload.items)) {
    messages.value[assistantIndex].citations = payload.items as CustomerCitation[]
  }
}
</script>

<style scoped lang="scss">
.chat-page {
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  background: #f5f6f8;
}

.service-header {
  display: flex;
  align-items: center;
  padding: 22rpx 28rpx;
  background: #fff;
  border-bottom: 1rpx solid #edf0f3;
}

.service-avatar {
  width: 76rpx;
  height: 76rpx;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 38rpx;
  font-weight: 700;
  background: linear-gradient(135deg, #fa436a, #ff8a65);
}

.service-intro {
  display: flex;
  flex: 1;
  flex-direction: column;
  margin-left: 18rpx;
}

.service-name {
  color: #303133;
  font-size: 30rpx;
  font-weight: 600;
}

.service-status {
  margin-top: 6rpx;
  color: #909399;
  font-size: 22rpx;
}

.new-chat {
  margin: 0;
  padding: 0 22rpx;
  height: 58rpx;
  line-height: 58rpx;
  color: #fa436a;
  font-size: 24rpx;
  background: #fff2f5;
  border: 0;
  border-radius: 30rpx;
}

.new-chat::after,
.send-button::after {
  border: 0;
}

.conversation-strip {
  flex: none;
  white-space: nowrap;
  padding: 18rpx 20rpx;
  background: #fff;
}

.conversation-chip {
  display: inline-flex;
  max-width: 300rpx;
  margin-right: 14rpx;
  padding: 12rpx 20rpx;
  color: #606266;
  background: #f5f6f8;
  border-radius: 24rpx;
  vertical-align: middle;
}

.conversation-chip.active {
  color: #fa436a;
  background: #fff0f3;
}

.conversation-preview {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 22rpx;
}

.message-list {
  flex: 1;
  min-height: 0;
  padding: 24rpx 24rpx 12rpx;
  box-sizing: border-box;
}

.welcome-card {
  display: flex;
  flex-direction: column;
  margin: 20rpx 0 30rpx;
  padding: 28rpx;
  background: #fff;
  border-radius: 18rpx;
}

.welcome-title {
  color: #303133;
  font-size: 30rpx;
  font-weight: 600;
}

.welcome-copy,
.welcome-hint {
  margin-top: 16rpx;
  color: #606266;
  font-size: 25rpx;
  line-height: 1.6;
}

.welcome-hint {
  color: #909399;
  font-size: 22rpx;
}

.message-row {
  display: flex;
  margin: 18rpx 0;
}

.message-row.user {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 82%;
  padding: 20rpx 24rpx;
  color: #303133;
  background: #fff;
  border-radius: 18rpx 18rpx 18rpx 4rpx;
  box-shadow: 0 4rpx 16rpx rgba(20, 30, 50, 0.04);
}

.message-row.user .message-bubble {
  color: #fff;
  background: #fa436a;
  border-radius: 18rpx 18rpx 4rpx 18rpx;
}

.message-content {
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 27rpx;
  line-height: 1.65;
}

.citations {
  display: flex;
  flex-direction: column;
  margin-top: 14rpx;
  padding-top: 12rpx;
  border-top: 1rpx solid #edf0f3;
}

.citation {
  color: #909399;
  font-size: 21rpx;
  line-height: 1.6;
}

.message-bottom {
  height: 2rpx;
}

.composer {
  display: flex;
  align-items: flex-end;
  gap: 16rpx;
  padding: 18rpx 22rpx calc(18rpx + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1rpx solid #edf0f3;
}

.message-input {
  flex: 1;
  max-height: 180rpx;
  min-height: 72rpx;
  padding: 16rpx 20rpx;
  box-sizing: border-box;
  color: #303133;
  font-size: 26rpx;
  line-height: 1.5;
  background: #f5f6f8;
  border-radius: 16rpx;
}

.send-button {
  flex: none;
  width: 112rpx;
  height: 72rpx;
  margin: 0;
  padding: 0;
  line-height: 72rpx;
  color: #fff;
  font-size: 25rpx;
  background: #fa436a;
  border-radius: 16rpx;
}

.send-button[disabled] {
  background: #f6a0b1;
}
</style>
