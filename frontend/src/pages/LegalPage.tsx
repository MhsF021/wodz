import { Link } from 'react-router';

const contact = <a href="mailto:mhsferreira021@gmail.com">mhsferreira021@gmail.com</a>;

export default function LegalPage({ privacy = false }: { privacy?: boolean }) {
  return <article className="page page--narrow legal-page">
    <p className="crumb"><Link to="/">WODZ</Link></p>
    <h1 className="page-title">{privacy ? 'Política de Privacidade' : 'Termos e Políticas de Compra'}</h1>
    <p className="note">Versão checkout-v2 · Atualização: 12 de outubro de 2026</p>
    {privacy ? <>
      <h2>1. Quem trata seus dados</h2>
      <p>O WODZ oferece ferramentas para cadastro, inscrições, organização e acompanhamento de campeonatos. Trata os dados necessários ao funcionamento da plataforma. A organização de cada evento trata os dados das suas inscrições para realizar o campeonato. O Mercado Pago trata os dados da cobrança conforme suas próprias condições e políticas.</p>
      <p>Para assuntos de privacidade relativos ao WODZ, entre em contato por {contact}. Para assuntos específicos da realização do campeonato, identifique o evento e a equipe no pedido; o atendimento poderá envolver sua organização.</p>
      <h2>2. Dados e finalidades</h2>
      <ul>
        <li><strong>Conta:</strong> nome, e-mail, sexo, telefone quando informado, perfil de acesso e senha protegida por hash, para autenticação, verificação da categoria, atendimento e administração da conta. Ao usar login Google, recebemos os dados de identidade necessários a esse vínculo.</li>
        <li><strong>Inscrição:</strong> dados de equipe e integrantes, incluindo nome, e-mail, CPF, data de nascimento, gênero e informações esportivas fornecidas no cadastro, para identificar os participantes e verificar a categoria escolhida.</li>
        <li><strong>Competição:</strong> categorias, horários, heats, resultados, classificações e imagens de identificação cadastradas pelo organizador, para apresentar e acompanhar o evento.</li>
        <li><strong>Pagamento:</strong> valor, identificadores da cobrança, forma, situação, data de confirmação e registro da versão e data do aceite, para emitir, retomar e conciliar a inscrição. O cartão é preenchido no ambiente do Mercado Pago.</li>
        <li><strong>Segurança:</strong> dados técnicos necessários para autenticação, prevenção de abuso, investigação de falhas e proteção do serviço.</li>
      </ul>
      <h2>3. Fundamentos do tratamento</h2>
      <p>O tratamento necessário ao cadastro, à inscrição e ao pagamento atende à execução do serviço solicitado e aos procedimentos relacionados ao contrato. Outros registros podem ser necessários ao cumprimento de obrigações legais, ao exercício de direitos e à proteção do serviço, observadas as bases e os limites da LGPD. O aceite destes documentos registra sua ciência das condições; não autoriza finalidades diferentes das descritas.</p>
      <h2>4. Compartilhamento e informações públicas</h2>
      <p>A organização acessa as informações necessárias à gestão de seu evento. O Mercado Pago recebe os dados necessários ao processamento financeiro; o Google participa da autenticação quando você escolhe esse login. Prestadores de infraestrutura podem tratar dados necessários à hospedagem e segurança. Esses fornecedores podem utilizar infraestrutura fora do Brasil, conforme suas políticas e as regras aplicáveis à transferência internacional.</p>
      <p>Nomes da equipe e dos atletas, categoria, programação, resultados e classificação podem aparecer nas páginas públicas do campeonato e na busca do leaderboard. Os dados completos de cadastro dos integrantes não compõem o placar público. Preserve o link e o código da inscrição: eles permitem consultar e acompanhar seu pagamento.</p>
      <h2>5. Armazenamento, segurança e duração</h2>
      <p>O WODZ utiliza controles de acesso por perfil e evento, proteção das senhas, cifragem das credenciais de recebimento, e verificação do pagamento no servidor. A sessão de acesso é mantida na aba do navegador. Dados permanecem enquanto necessários às finalidades informadas e às obrigações de conservação aplicáveis, com acesso limitado.</p>
      <p>A exclusão da conta desativa o acesso e descaracteriza seus dados de perfil. Registros de inscrição, competição e pagamento podem precisar ser preservados para obrigações legais e exercício de direitos. A exclusão pode exigir resolver campeonatos ativos ou inscrições confirmadas sob sua responsabilidade. A desconexão de um provedor externo não elimina automaticamente os dados mantidos por ele.</p>
      <h2>6. Seus direitos e atendimento</h2>
      <p>Você pode solicitar confirmação do tratamento, acesso, correção, informação sobre compartilhamento, anonimização, bloqueio ou eliminação de dados nas hipóteses legais, portabilidade nos limites regulamentares e revisão de decisões automatizadas quando aplicável. Pode também questionar o tratamento e revogar consentimentos que tenha fornecido para finalidades opcionais. Acesse “Minha conta” para atualizar ou excluir o perfil, ou envie sua solicitação para {contact}.</p>
      <p>Podemos solicitar informações proporcionais para confirmar sua identidade e proteger seus dados. Não envie senhas, tokens ou dados completos de cartão por e-mail. Pedidos serão analisados nos prazos e condições legais. Você também pode procurar a Autoridade Nacional de Proteção de Dados.</p>
      <h2>7. Atualizações</h2>
      <p>Mudanças relevantes serão apresentadas nesta página com nova versão e data. Novas finalidades que dependam de consentimento serão tratadas separadamente.</p>
      <p>Referência: <a href="https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm" target="_blank" rel="noopener noreferrer">Lei Geral de Proteção de Dados</a>.</p>
    </> : <>
      <h2>1. Serviço e responsabilidades</h2>
      <p>O WODZ é uma plataforma para divulgar campeonatos, cadastrar equipes e atletas, receber inscrições e acompanhar programação e resultados. O organizador define e realiza seu evento, incluindo categorias, regras, local, calendário e atendimento dos participantes. O WODZ fornece as ferramentas da plataforma e o Mercado Pago processa os pagamentos.</p>
      <p>Ao se inscrever, confira as informações publicadas do campeonato e da categoria. O responsável pela equipe deve fornecer dados corretos, comunicar as condições aos integrantes e ter autorização para cadastrar seus dados. Regras de participação e informações sobre segurança da atividade devem ser verificadas com a organização antes da inscrição.</p>
      <h2>2. Pré-inscrição e confirmação</h2>
      <p>O cadastro de uma inscrição paga gera uma pré-inscrição. Sua confirmação depende da aprovação do pagamento consultada pelo WODZ no Mercado Pago. Criar uma cobrança, abrir o checkout ou retornar à plataforma não equivale à aprovação. A situação pode ser acompanhada pelo link da inscrição e em “Minhas equipes”. Inscrições gratuitas seguem as condições exibidas pelo evento.</p>
      <h2>3. Preço e meios de pagamento</h2>
      <p>Novas inscrições são cotadas pelo preço por atleta multiplicado pelo número de integrantes da equipe. O total salvo na inscrição é apresentado no checkout antes de continuar; reajustes posteriores da categoria não modificam essa cotação. Não acrescentamos uma taxa de serviço ao atleta. Condições de parcelamento e eventuais encargos do meio escolhido são apresentados pelo Mercado Pago antes da conclusão.</p>
      <p>Ao continuar para o pagamento, você é direcionado ao Mercado Pago, onde escolhe entre os meios disponibilizados e informa os dados necessários.</p>
      <p>Uma cobrança válida é retomada para evitar duplicidade. Para trocar a forma de pagamento após sua emissão, aguarde o vencimento. Cobranças recusadas ou vencidas podem ser tentadas novamente enquanto o evento aceitar pagamento. Em ambiente de teste, utilize os recursos e dados de teste indicados pelo Mercado Pago.</p>
      <h2>4. Cancelamento, arrependimento e reembolso</h2>
      <p>Solicite cancelamento ou reembolso à organização do evento ou ao atendimento WODZ por {contact}, informando campeonato, equipe e código da inscrição. A situação também pode ser acompanhada em “Minhas equipes”. Cancelar o registro da equipe não executa automaticamente um reembolso financeiro.</p>
      <p>Os direitos previstos no Código de Defesa do Consumidor são preservados. Nas hipóteses do art. 49, o consumidor pode exercer o direito de arrependimento no prazo legal de sete dias, contado conforme o caso da assinatura ou do recebimento do produto ou serviço, com devolução dos valores na forma da lei. As regras do evento não afastam direitos legais.</p>
      <p>Em caso de cancelamento, adiamento, cobrança duplicada ou falha na prestação do serviço, procure o atendimento para análise e encaminhamento à organização. Reembolsos financeiros são processados pelo recebedor através do Mercado Pago; prazo de disponibilização depende do meio de pagamento e do processamento aplicável. Guarde os comprovantes.</p>
      <h2>5. Conta, dados e aceite</h2>
      <p>Proteja seu acesso e o link da inscrição. O uso de dados pessoais está descrito na <Link to="/privacidade" target="_blank" rel="noopener noreferrer">Política de Privacidade</Link>. O aceite no checkout registra a data e a versão destas condições. Uso de imagem para divulgação, quando proposto pela organização, exige informações e autorização específicas; o aceite do pagamento não concede uma autorização geral.</p>
      <h2>6. Atendimento e alterações</h2>
      <p>O atendimento da plataforma está disponível por {contact}. Informe o contexto e o código da inscrição para permitir sua localização. Alterações relevantes destes documentos serão publicadas com versão e data; os registros de aceite anteriores serão preservados.</p>
      <p>Referência: <a href="https://www.planalto.gov.br/ccivil_03/leis/l8078compilado.htm" target="_blank" rel="noopener noreferrer">Código de Defesa do Consumidor</a>.</p>
    </>}
    <div className="row-actions"><Link to={privacy ? '/termos' : '/privacidade'}>{privacy ? 'Termos e Políticas de Compra' : 'Política de Privacidade'}</Link></div>
  </article>;
}
