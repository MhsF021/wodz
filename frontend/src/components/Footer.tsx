import type { ComponentType } from 'react';
import { Headset, Mail, MapPin, Phone, Share2, type LucideProps } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { logoFor } from '../lib/theme';
import { GithubIcon, InstagramIcon, LinkedinIcon } from './BrandIcons';

interface FooterLink {
  label: string;
  value: string;
  href: string;
  Icon: ComponentType<LucideProps>;
  external?: boolean;
}

const ADDRESS = 'Rua Jonathas de Vasconcelos, 750, Boa Viagem - Recife/PE';

const SUPPORT: FooterLink[] = [
  {
    label: 'Endereço',
    value: ADDRESS,
    href: `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(ADDRESS)}`,
    Icon: MapPin,
    external: true,
  },
  { label: 'E-mail', value: 'mhsferreira021@gmail.com', href: 'mailto:mhsferreira021@gmail.com', Icon: Mail },
  { label: 'Telefone', value: '+55 81 98183-5196', href: 'tel:+5581981835196', Icon: Phone },
];

const SOCIAL: FooterLink[] = [
  { label: 'LinkedIn', value: '/in/mhsf021', href: 'https://www.linkedin.com/in/mhsf021/', Icon: LinkedinIcon, external: true },
  { label: 'GitHub', value: '@Mhsf021', href: 'https://github.com/Mhsf021/', Icon: GithubIcon, external: true },
  { label: 'Instagram', value: '@matheusfr_f', href: 'https://www.instagram.com/matheusfr_f', Icon: InstagramIcon, external: true },
];

export function Footer() {
  const { user } = useAuth();
  return (
    <footer className="footer">
      <div className="footer__inner">
        <div className="footer__brand">
          <img className="footer__logo" src={logoFor(user?.role)} alt="" width={36} height={36} />
          <div>
            <p className="footer__name">WODZ</p>
            <p className="footer__tagline">Inscrições, baterias e leaderboard de campeonatos de CrossFit.</p>
          </div>
        </div>
        <FooterColumn id="footer-support" title="Precisa de suporte?" Icon={Headset} links={SUPPORT} />
        <FooterColumn id="footer-social" title="Siga" Icon={Share2} links={SOCIAL} />
      </div>
      <div className="footer__bottom">
        <p className="footer__bottom-inner">© {new Date().getFullYear()} WODZ</p>
      </div>
    </footer>
  );
}

function FooterColumn({
  id,
  title,
  Icon,
  links,
}: {
  id: string;
  title: string;
  Icon: ComponentType<LucideProps>;
  links: FooterLink[];
}) {
  return (
    <section aria-labelledby={id}>
      <h2 id={id} className="footer__title">
        <Icon size={18} aria-hidden />
        {title}
      </h2>
      <ul className="footer__list">
        {links.map(({ label, value, href, Icon: ItemIcon, external }) => (
          <li key={label}>
            <a className="footer__item" href={href}
              {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}>
              <ItemIcon size={20} aria-hidden />
              <span>
                <span className="footer__label">{label}</span>
                <span className="footer__value">{value}</span>
                {external && <span className="sr-only"> (abre em nova aba)</span>}
              </span>
            </a>
          </li>
        ))}
      </ul>
    </section>
  );
}
