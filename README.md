# Projeto: Processamento de Eventos CSV na AWS

Este repositório contém o código e a infraestrutura para um pipeline de processamento de dados que ingere arquivos CSV, os processa, enfileira eventos válidos e os consome para armazenamento final. A arquitetura é construída sobre serviços AWS como S3, SQS, Parameter Store, e aplicações customizadas (`app-producer` e `app-consumer`) rodando em AWS Fargate.

## Conteúdo

1.  [Visão Geral da Arquitetura](#1-visão-geral-da-arquitetura)
2.  [Pré-requisitos da Máquina de Desenvolvimento](#2-pré-requisitos-da-máquina-de-desenvolvimento)
3.  [Configuração da AWS](#3-configuração-da-aws)
4.  [Deploy Completo do Ambiente](#4-deploy-completo-do-ambiente)
5.  [Uso do Pipeline](#5-uso-do-pipeline)
6.  [Monitoramento e Logs](#6-monitoramento-e-logs)
7.  [Configurações](#7-configurações)
8.  [Solução de Problemas](#8-solução-de-problemas)

---

## 1. Visão Geral da Arquitetura

O processo é iniciado com o **upload de um arquivo CSV para um bucket S3 de entrada**. Este evento aciona o **`app-producer`** (rodando em AWS Fargate), que:
*   Lê o arquivo CSV do S3.
*   Valida, transforma os dados (camelCase, enriquecimento).
*   Armazena dados não processados ou inválidos em um S3 de "dados não processados".
*   Envia "Eventos válidos" (JSON) para uma fila SQS.

O **`app-consumer`** (rodando em AWS Fargate) escuta a fila SQS, consome os eventos, realiza o processamento final e os armazena em um S3 de "dados processados".

Configurações sensíveis são gerenciadas via **AWS Parameter Store** e **Secrets Manager**. Monitoramento e observabilidade são fornecidos por **CloudWatch Logs** e **Amazon CloudWatch Metrics & Alarms**. A infraestrutura é provisionada via **Terraform**.

### Fluxo Detalhado (Referência a `poupanca - CSV Ingestion 2.pdf`):
1.  **Ingestão:** Arquivo CSV depositado em um bucket S3.
2.  **Processamento inicial (`app-producer`):** Verifica configurações no SSM Parameter Store e Secrets Manager.
3.  **Armazenamento de regras:** AWS Parameter Store armazena regras de processamento.
4.  **Descarte de dados:** Dados rejeitados vão para S3 de "dados não processados".
5.  **Processamento (`app-producer`):** Lê S3, aplica transformações, validações (ignora negativos ou campos obrigatórios faltantes), enriquecimento (timestamp, classificação crédito/débito).
6.  **Distribuição:** Eventos processados distribuídos via SQS para desacoplamento.
7.  **Consumo (`app-consumer`):** Aplicação consome SQS e persiste dados processados.
8.  **Saída:** Dados processados salvos em S3 de "dados processados".
*   **Infraestrutura:** Toda via IaC (Terraform).

---

## 2. Pré-requisitos da Máquina de Desenvolvimento

Certifique-se de que sua máquina possui as seguintes ferramentas instaladas e configuradas:

*   **Git:** Para clonar o repositório.
*   **Editor de Texto / IDE:** Visual Studio Code ou IntelliJ IDEA (recomendado para Kotlin/Java/Spring Boot).
*   **Java JDK 17+:** Para as aplicações Spring Boot.
*   **Kotlin:** Necessário para o `app-consumer`.
*   **Docker Desktop:** Para construir e gerenciar imagens Docker.
*   **AWS CLI v2:** Para interagir com a AWS e autenticar no ECR.
*   **Terraform 1.0+:** Para provisionar a infraestrutura AWS.
*   **Gradle:** Para construir os projetos Spring Boot.

---

## 3. Configuração da AWS

1.  **Credenciais AWS:** Configure suas credenciais AWS com permissões suficientes para criar e gerenciar S3, SQS, ECR, ECS Fargate, IAM, CloudWatch, Parameter Store, Secrets Manager.
    ```bash
    aws configure
    # Forneça seu AWS Access Key ID, AWS Secret Access Key, Default region name (ex: us-east-1)
    ```
2.  **Região AWS:** Todos os recursos serão deployados na região definida (ex: `us-east-1` no `ica/variables.tf`).

---

## 4. Deploy Completo do Ambiente

Siga os passos abaixo para subir todo o ambiente, incluindo a infraestrutura AWS e as aplicações.

**IMPORTANTE:**
*   Edite `iac/variables.tf` para personalizar nomes de buckets (globalmente únicos), o ARN do seu segredo do Google Drive e defina o URI das imagens Docker ECR para `app-producer` e `app-consumer`. Você precisará atualizar essas variáveis após o push das imagens para o ECR.
*   Substitua `YOUR_ACCOUNT_ID` e `YOUR_REGION` nos comandos ECR pelos seus dados reais.

### Passo a Passo

1.  **Deploy da Infraestrutura Base (Terraform)**
    ```bash
    # 1. Navegue até o diretório de infraestrutura
    cd iac

    # 2. Inicialize o Terraform
    terraform init

    # 3. Revise o plano de execução (opcional)
    terraform plan

    # 4. Execute o deploy da infraestrutura
    terraform apply
    # Confirme com 'yes' quando solicitado.
    ```
    O Terraform irá provisionar a VPC, subnets, S3 Buckets, SQS Queue, ECS Cluster, IAM Roles, CloudWatch Log Groups e SSM Parameters.

2.  **Deploy do `app-producer` (Aplicação e Serviço Fargate)**

    ```bash
    # 1. Navegue até o diretório da aplicação producer
    cd ../app-producer

    # 2. Construa o JAR da aplicação
    ./gradlew clean build

    # 3. Construa a Imagem Docker
    docker build -t app-producer .

    # 4. Autentique o Docker no Amazon ECR
    aws ecr get-login-password --region YOUR_REGION | docker login --username AWS --password-stdin YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com

    # 5. Marque (tag) e faça push da Imagem Docker para o ECR
    # URI Exemplo: YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/transaction-processor-app-producer:latest
    docker tag app-producer:latest YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/transaction-processor-app-producer:latest
    docker push YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/transaction-processor-app-producer:latest

    # 6. Anote o URI completo da imagem para usar no próximo passo!
    # Ex: 'YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/transaction-processor-app-producer:latest'

    # 7. Volte ao diretório infra
    cd ../../iac

    # 8. Atualize a variável 'app_producer_docker_image' em 'iac/variables.tf' com o URI anotado.
    #    Em seguida, aplique as mudanças para que o serviço ECS use a nova imagem.
    terraform apply
    # Confirme com 'yes' quando solicitado.
    ```

3.  **Deploy do `app-consumer` (Aplicação e Serviço Fargate)**

    ```bash
    # 1. Navegue até o diretório da aplicação consumer
    cd ../app-consumer

    # 2. Construa o JAR da aplicação
    ./gradlew clean build

    # 3. Construa a Imagem Docker
    docker build -t app-consumer .

    # 4. Autentique o Docker no Amazon ECR (pule se já autenticado e token válido)
    aws ecr get-login-password --region YOUR_REGION | docker login --username AWS --password-stdin YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com

    # 5. Marque (tag) e faça push da Imagem Docker para o ECR
    # URI Exemplo: YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/transaction-processor-app-consumer:latest
    docker tag app-consumer:latest YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/transaction-processor-app-consumer:latest
    docker push YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/transaction-processor-app-consumer:latest

    # 6. Anote o URI completo da imagem para usar no próximo passo!
    # Ex: 'YOUR_ACCOUNT_ID.dkr.ecr.YOUR_REGION.amazonaws.com/transaction-processor-app-consumer:latest'

    # 7. Volte ao diretório infra
    cd ../../iac

    # 8. Atualize a variável 'app_consumer_docker_image' em 'iac/variables.tf' com o URI anotado.
    #    Em seguida, aplique as mudanças para que o serviço ECS use a nova imagem.
    terraform apply
    # Confirme com 'yes' quando solicitado.
    ```

---

## 5. Uso do Pipeline

1.  Verifique o status dos serviços ECS (`app-producer`, `app-consumer`) no console da AWS para garantir que estão rodando.
2.  Faça o upload de um arquivo CSV de teste para o **bucket S3 de entrada** (`s3://<NOME_DO_SEU_BUCKET_ENTRADA>/input_data/data.csv`).
    *   Este upload, após configurado o trigger, acionará o `app-producer`.
    *   O `app-producer` processará o CSV e enviará eventos válidos para o SQS.
    *   O `app-consumer` consumirá os eventos do SQS e os armazenará no **bucket S3 de "dados processados"**.
3.  Verifique o S3 de "dados processados" para os resultados e o S3 de "dados não processados" para eventuais rejeições.

---

## 6. Monitoramento e Logs

*   **CloudWatch Logs**: Acesse o console do CloudWatch, navegue até "Log groups" para visualizar os logs de suas aplicações Fargate (`/ecs/transaction-processor-app-producer`, `/ecs/transaction-processor-app-consumer`).
*   **CloudWatch Metrics & Alarms**: Métricas básicas de S3, SQS e ECS Fargate são coletadas automaticamente. Alarmes customizados podem ser configurados no CloudWatch para eventos específicos.

---

## 7. Configurações

As configurações sensíveis e variáveis de ambiente são gerenciadas através do AWS Parameter Store e Secrets Manager. As aplicações (`app-producer` e `app-consumer`) são configuradas para ler esses parâmetros na inicialização, ou os valores são injetados diretamente nas variáveis de ambiente das Task Definitions do Fargate.

---

## 8. Solução de Problemas

*   **Logs**: Sempre comece verificando os logs no CloudWatch para as aplicações `app-producer` e `app-consumer`.
*   **Permissões IAM**: Confirme se as roles IAM associadas às tarefas Fargate têm as permissões necessárias para acessar S3, SQS, Parameter Store, Secrets Manager, etc.
*   **Configurações**: Verifique se todos os parâmetros necessários estão definidos no AWS Parameter Store e se as aplicações os estão lendo corretamente.
*   **Triggers S3**: Se o `app-producer` não é acionado, verifique se a notificação de evento S3 está configurada no bucket de entrada (e.g., via Lambda que aciona o producer).
*   **Backlog SQS**: Se a fila SQS estiver crescendo, o `app-consumer` pode estar com problemas ou não processando rápido o suficiente. Verifique a métrica `ApproximateNumberOfMessagesVisible` no CloudWatch.