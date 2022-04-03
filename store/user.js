export const state = () => ({
  user: null,
  serverConnectionConfig: null,
  userAudiobookData: [],
  settings: {
    mobileOrderBy: 'addedAt',
    mobileOrderDesc: true,
    mobileFilterBy: 'all',
    orderBy: 'book.title',
    orderDesc: false,
    filterBy: 'all',
    playbackRate: 1,
    bookshelfCoverSize: 120
  },
  settingsListeners: [],
  userAudiobooksListeners: []
})

export const getters = {
  getIsRoot: (state) => state.user && state.user.type === 'root',
  getToken: (state) => {
    return state.user ? state.user.token : null
  },
  getServerAddress: (state) => {
    return state.serverConnectionConfig ? state.serverConnectionConfig.address : null
  },
  getUserLibraryItemProgress: (state) => (libraryItemId) => {
    if (!state.user.libraryItemProgress) return null
    return state.user.libraryItemProgress.find(li => li.id == libraryItemId)
  },
  getUserBookmarksForItem: (state) => (libraryItemId) => {
    if (!state.user.bookmarks) return []
    return state.user.bookmarks.filter(bm => bm.libraryItemId === libraryItemId)
  },
  getUserAudiobookData: (state, getters) => (audiobookId) => {
    return getters.getUserAudiobook(audiobookId)
  },
  getUserAudiobook: (state, getters) => (audiobookId) => {
    return state.userAudiobookData.find(uabd => uabd.audiobookId === audiobookId)
  },
  getUserSetting: (state) => (key) => {
    return state.settings ? state.settings[key] || null : null
  },
  getFilterOrderKey: (state) => {
    return Object.values(state.settings).join('-')
  }
}

export const actions = {
  async updateUserSettings({ commit }, payload) {
    if (this.$server.connected) {
      var updatePayload = {
        ...payload
      }
      return this.$axios.$patch('/api/me/settings', updatePayload).then((result) => {
        if (result.success) {
          commit('setSettings', result.settings)
          return true
        } else {
          return false
        }
      }).catch((error) => {
        console.error('Failed to update settings', error)
        return false
      })
    } else {
      console.log('Update settings without server')
      commit('setSettings', payload)
    }
  },
  async loadOfflineUserAudiobookData({ state, commit }) {
    var localUserAudiobookData = await this.$sqlStore.getAllUserAudiobookData() || []
    if (localUserAudiobookData.length) {
      console.log('loadOfflineUserAudiobookData found', localUserAudiobookData.length, 'user audiobook data')
      commit('setAllUserAudiobookData', localUserAudiobookData)
    } else {
      console.log('loadOfflineUserAudiobookData No user audiobook data')
    }
  },
  async syncUserAudiobookData({ state, commit }) {
    if (!state.user) {
      console.error('Sync user audiobook data invalid no user')
      return
    }
    var localUserAudiobookData = await this.$sqlStore.getAllUserAudiobookData() || []
    this.$axios.$post(`/api/syncUserAudiobookData`, { data: localUserAudiobookData }).then(async (abData) => {
      console.log('Synced user audiobook data', abData)
      await this.$sqlStore.setAllUserAudiobookData(abData)
    }).catch((error) => {
      console.error('Failed to sync user ab data', error)
    })
  },
  async updateUserAudiobookData({ state, commit }, uabdUpdate) {
    var userAbData = state.userAudiobookData.find(uab => uab.audiobookId === uabdUpdate.audiobookId)
    if (!userAbData) {
      uabdUpdate.startedAt = Date.now()
      this.$sqlStore.setUserAudiobookData(uabdUpdate)
    } else {
      var mergedUabData = { ...userAbData }
      for (const key in uabdUpdate) {
        mergedUabData[key] = uabdUpdate[key]
      }
      this.$sqlStore.setUserAudiobookData(mergedUabData)
    }
  }
}

export const mutations = {
  logout(state) {
    state.user = null
    state.serverConnectionConfig = null
  },
  setUser(state, user) {
    state.user = user
  },
  setServerConnectionConfig(state, serverConnectionConfig) {
    state.serverConnectionConfig = serverConnectionConfig
  },
  setUserAudiobookData(state, abdata) {
    var index = state.userAudiobookData.findIndex(uab => uab.audiobookId === abdata.audiobookId)
    if (index >= 0) {
      state.userAudiobookData.splice(index, 1, abdata)
    } else {
      state.userAudiobookData.push(abdata)
    }
  },
  removeUserAudiobookData(state, audiobookId) {
    state.userAudiobookData = state.userAudiobookData.filter(uab => uab.audiobookId !== audiobookId)
  },
  setAllUserAudiobookData(state, allAbData) {
    state.userAudiobookData = allAbData
  },
  setSettings(state, settings) {
    if (!settings) return

    var hasChanges = false
    for (const key in settings) {
      if (state.settings[key] !== settings[key]) {
        if (key === 'mobileOrderBy' && settings[key] === 'recent') {
          settings[key] = 'addedAt'
        }
        hasChanges = true
        state.settings[key] = settings[key]
      }
    }
    if (hasChanges) {
      this.$localStore.setUserSettings({ ...state.settings })
      state.settingsListeners.forEach((listener) => {
        listener.meth(state.settings)
      })
    }
  },
  addSettingsListener(state, listener) {
    var index = state.settingsListeners.findIndex(l => l.id === listener.id)
    if (index >= 0) state.settingsListeners.splice(index, 1, listener)
    else state.settingsListeners.push(listener)
  },
  removeSettingsListener(state, listenerId) {
    state.settingsListeners = state.settingsListeners.filter(l => l.id !== listenerId)
  },
  addUserAudiobookListener(state, listener) {
    var index = state.userAudiobooksListeners.findIndex(l => l.id === listener.id)
    if (index >= 0) state.userAudiobooksListeners.splice(index, 1, listener)
    else state.userAudiobooksListeners.push(listener)
  },
  removeUserAudiobookListener(state, listenerId) {
    state.userAudiobooksListeners = state.userAudiobooksListeners.filter(l => l.id !== listenerId)
  }
}