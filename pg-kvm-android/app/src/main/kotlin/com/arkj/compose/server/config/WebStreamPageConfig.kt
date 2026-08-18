package com.arkj.compose.server.config

import android.content.Context
import com.yanzhenjie.andserver.annotation.Config
import com.yanzhenjie.andserver.framework.config.WebConfig
import com.yanzhenjie.andserver.framework.website.AssetsWebsite

@Config
class WebStreamPageConfig: WebConfig {
  override fun onConfig(
    context: Context?,
    delegate: WebConfig.Delegate?
  ) {
    if (context == null || delegate == null) {
      throw IllegalArgumentException("Context and Delegate must not be null")
    }
    delegate.addWebsite(AssetsWebsite(context, "/web/"))
  }
}