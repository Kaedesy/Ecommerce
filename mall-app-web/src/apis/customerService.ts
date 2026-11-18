import { http } from '@/utils/http'

export interface CustomerConversation {
  id: number
  preview: string
  status?: string
  updated_at?: string | null
}

export interface CustomerMessage {
  role: 'user' | 'assistant'
  content: string
  created_at?: string | null
}

export interface CustomerCitation {
  title?: string
  source?: string
  url?: string
}

export const getCustomerConversationsAPI = () =>
  http<{ items: CustomerConversation[] }>({
    method: 'GET',
    url: '/customer-service/conversations',
  })

export const getCustomerMessagesAPI = (conversationId: number) =>
  http<{ items: CustomerMessage[] }>({
    method: 'GET',
    url: `/customer-service/conversations/${conversationId}/messages`,
  })

export const getCustomerServiceBaseURL = () => import.meta.env.VITE_API_BASE_URL.replace(/\/$/, '')
