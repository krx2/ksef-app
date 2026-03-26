package pl.ksef.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String INVOICE_SEND_QUEUE    = "invoice.send.queue";
    public static final String INVOICE_SEND_DLQ      = "invoice.send.dlq";
    public static final String INVOICE_SEND_EXCHANGE  = "invoice.send.exchange";
    public static final String INVOICE_RESULT_QUEUE  = "invoice.result.queue";
    public static final String INVOICE_FETCH_QUEUE   = "invoice.fetch.queue";

    @Bean
    public Queue invoiceSendQueue() {
        return QueueBuilder.durable(INVOICE_SEND_QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", INVOICE_SEND_DLQ)
                .build();
    }

    @Bean
    public Queue invoiceSendDlq() {
        return QueueBuilder.durable(INVOICE_SEND_DLQ).build();
    }

    @Bean
    public Queue invoiceResultQueue() {
        return QueueBuilder.durable(INVOICE_RESULT_QUEUE).build();
    }

    @Bean
    public Queue invoiceFetchQueue() {
        return QueueBuilder.durable(INVOICE_FETCH_QUEUE).build();
    }

    @Bean
    public DirectExchange invoiceSendExchange() {
        return new DirectExchange(INVOICE_SEND_EXCHANGE);
    }

    @Bean
    public Binding invoiceSendBinding(Queue invoiceSendQueue, DirectExchange invoiceSendExchange) {
        return BindingBuilder.bind(invoiceSendQueue).to(invoiceSendExchange).with(INVOICE_SEND_QUEUE);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(5);
        return factory;
    }
}
