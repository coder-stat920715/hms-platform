package com.hms.notification.config;

import com.hms.notification.channel.LoggingChannel;
import com.hms.notification.channel.NotificationChannel;
import com.hms.notification.domain.ChannelType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sns.SnsClient;

@Configuration
public class ChannelConfig {

  /** Default mode: three logging channels so the whole flow can be exercised locally. */
  @Bean
  @ConditionalOnProperty(name = "hms.notification.mode", havingValue = "log", matchIfMissing = true)
  public NotificationChannel logEmailChannel() { return new LoggingChannel(ChannelType.EMAIL); }

  @Bean
  @ConditionalOnProperty(name = "hms.notification.mode", havingValue = "log", matchIfMissing = true)
  public NotificationChannel logSmsChannel() { return new LoggingChannel(ChannelType.SMS); }

  @Bean
  @ConditionalOnProperty(name = "hms.notification.mode", havingValue = "log", matchIfMissing = true)
  public NotificationChannel logPushChannel() { return new LoggingChannel(ChannelType.PUSH); }

  /** AWS clients use the default credential chain (IRSA on EKS) and region from AWS_REGION. */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "hms.notification.mode", havingValue = "aws")
  public SesV2Client sesClient() { return SesV2Client.create(); }

  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(name = "hms.notification.mode", havingValue = "aws")
  public SnsClient snsClient() { return SnsClient.create(); }
}
